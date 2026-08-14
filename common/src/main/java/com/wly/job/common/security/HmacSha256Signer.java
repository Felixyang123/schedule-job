package com.wly.job.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * HMAC-SHA256 请求签名工具（ADR-0006 §3.2）：以凭证摘要（token_hash）为密钥，
 * 对规范化请求串签名，Admin 派发签名、Worker 验签共用。
 *
 * <p>签名只提供<b>认证（MAC）</b>而非加密：jobname / executeParam 在 Netty 上仍为明文，
 * 可被读取但不可篡改、不可伪造。防重放由调用方配合时间戳（±30s）与 requestId 去重集完成，
 * 本类不承担。
 */
public final class HmacSha256Signer {

    private static final String ALGORITHM = "HmacSHA256";

    /** 规范化请求字段分隔符：字段值含该字符时由上层负责清洗（当前业务字段不含） */
    private static final char FIELD_SEPARATOR = '|';

    private HmacSha256Signer() {
    }

    /**
     * 规范化请求串：{@code requestId | jobname | executeParam | timestamp | nonce}。
     * <p>
     * {@code nonce} 复用 {@code requestId}（Spec 2026-08-11 §3.2，合法重试都会换新 ID）；
     * 显式列出保证字段顺序稳定，篡改任一字段都会导致签名不匹配。
     */
    public static String canonical(String requestId, String jobname, String executeParam,
                                   long timestamp, String nonce) {
        return safe(requestId) + FIELD_SEPARATOR
                + safe(jobname) + FIELD_SEPARATOR
                + safe(executeParam) + FIELD_SEPARATOR
                + timestamp + FIELD_SEPARATOR
                + safe(nonce);
    }

    /**
     * 计算签名：{@code HMAC-SHA256(keyBase64, canonical)} 的 Base64 编码。
     *
     * @param keyBase64 HMAC 密钥（Base64 编码，即 PBKDF2 派生的 256 bit 摘要）
     * @param canonical 规范化请求串（{@link #canonical} 的产物）
     */
    public static String sign(String keyBase64, String canonical) {
        byte[] mac = mac(keyBase64, canonical);
        return Base64.getEncoder().encodeToString(mac);
    }

    /**
     * 验签：常量时间比较期望签名与出示签名（防时序侧信道）。
     *
     * @param keyBase64   HMAC 密钥（Base64 编码）
     * @param canonical   规范化请求串
     * @param presented   请求携带的签名（Base64 编码）
     */
    public static boolean verify(String keyBase64, String canonical, String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = mac(keyBase64, canonical);
        return java.security.MessageDigest.isEqual(expected, Base64.getDecoder().decode(presented));
    }

    private static byte[] mac(String keyBase64, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(keyBase64), ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
