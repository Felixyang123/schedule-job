package com.wly.job.common.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证摘要工具：PBKDF2WithHmacSHA256 + 随机盐派生不可逆摘要（ADR-0006）。
 *
 * <p>凭证体系（按「应用身份 + 环境」发放）下，Admin 数据库只存 PBKDF2 摘要，
 * 明文仅在创建/轮换响应中返回一次；校验时对出示明文派生后常量时间比较。
 * 纯 JDK 实现，不引入 spring-security-crypto（AGENTS.md §5.1）。
 *
 * <p>派生参数（salt / iterations）需随摘要一同持久化，验签方（Worker）按同一
 * 参数重新派生，因此 {@link ScheduleJobRequest} 需携带 salt / iterations。
 */
public final class Pbkdf2Digest {

    /** 迭代次数：120_000（OWASP 对 PBKDF2-HMAC-SHA256 的 2023 推荐值下限） */
    public static final int DEFAULT_ITERATIONS = 120_000;

    /** 随机盐长度（字节） */
    public static final int SALT_LENGTH_BYTES = 16;

    /** 派生密钥长度（bit），PBKDF2 输出 256 bit 供 HMAC-SHA256 使用 */
    public static final int DERIVED_KEY_BITS = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Pbkdf2Digest() {
    }

    /**
     * 生成随机盐（Base64 编码）。
     */
    public static String randomSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 派生摘要：{@code PBKDF2WithHmacSHA256(password, salt, iterations, 256bit)}。
     *
     * @param plaintext 明文凭证
     * @param salt      盐（Base64 编码，来自持久化或请求）
     * @param iterations 迭代次数
     * @return 派生密钥（256 bit，HMAC-SHA256 密钥 / 存储摘要双用，Base64 编码）
     */
    public static String derive(String plaintext, String salt, int iterations) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("plaintext must not be blank");
        }
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("salt must not be blank");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive, got: " + iterations);
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(),
                    Base64.getDecoder().decode(salt), iterations, DERIVED_KEY_BITS);
            byte[] derived = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(derived);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 derivation failed", e);
        }
    }

    /**
     * 派生并生成盐（创建凭证时使用）：生成随机盐并返回 {@code salt:iterations:digest} 三段。
     * 三段以 {@code :} 分隔，其中 digest 为派生密钥（存储摘要 / HMAC 密钥双用）。
     *
     * @param plaintext 明文凭证
     * @return 三元组（salt / iterations / digest），供调用方持久化
     */
    public static Derivation deriveNew(String plaintext) {
        String salt = randomSalt();
        String digest = derive(plaintext, salt, DEFAULT_ITERATIONS);
        return new Derivation(salt, DEFAULT_ITERATIONS, digest);
    }

    /**
     * 常量时间比较两个 Base64 编码的摘要（防时序侧信道）。
     */
    public static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 派生结果三元组（salt / iterations / digest）。
     */
    public record Derivation(String salt, int iterations, String digest) {
    }
}
