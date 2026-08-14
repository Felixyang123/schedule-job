package com.wly.job.core.security;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.security.HmacSha256Signer;
import com.wly.job.common.security.Pbkdf2Digest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 侧 RPC 请求验签器（ADR-0006 §3.2 / Spec 2026-08-11 §3.2、§3.3）。
 *
 * <p>职责：用本地明文凭证按请求携带的 salt/iterations 派生同一 HMAC 密钥，
 * 对规范化请求验签；配合 ±30s 时间窗与 requestId 去重集拒绝重放。
 *
 * <p><b>线程模型（关键约束）</b>：{@link #preCheck} 必须在 Netty I/O 线程执行，
 * 只做无派生的轻量检查（时间窗 / 版本 / requestId 去重），重放与超时请求在此被拒、
 * <b>不进业务线程池</b>；{@link #authenticate} 在业务线程池执行，PBKDF2 派生（约 100ms）
 * 通过 {@code (version, salt, iterations)} 缓存避免重复开销（上限 4、淘汰最小版本号），
 * 命中后仅一次 HMAC（Spec 决策 #25）。
 *
 * <p>去重集：{@code requestId -> 到达时间}，TTL = 2×时间窗（60s）懒清理；容量上限
 * {@link #MAX_TRACKED_REQUESTS}（10 万），超限先清过期、仍超则拒绝新请求，宁可短暂
 * 拒合法流量也不 OOM（Spec §3.3）。合法重试都会换新 requestId（REQUEUE / 下个火点），
 * 不会误杀。
 */
public class RpcRequestAuthenticator {

    /** 时间窗（±30s）：超出即拒，防历史截获请求重放 */
    public static final long TIME_WINDOW_MS = 30_000L;

    /** 去重条目 TTL（2×时间窗 = 60s），与合法派发重试间隔无冲突 */
    public static final long TRACK_TTL_MS = 2 * TIME_WINDOW_MS;

    /** 去重集容量上限：宁可短暂拒绝合法流量，也不允许进程 OOM */
    public static final int MAX_TRACKED_REQUESTS = 100_000;

    /** 派生密钥缓存上限：只保留最近使用的 4 个版本 */
    static final int MAX_DERIVED_KEY_CACHE = 4;

    /** 鉴权结果 */
    public enum AuthResult {
        /** 通过（后续需在业务线程池完成签名比较） */
        OK,
        /** 时间戳超 ±30s 时间窗 */
        TIMESTAMP_EXPIRED,
        /** requestId 重放（去重集已存在） */
        REPLAY,
        /** 凭证版本与本地不符 */
        VERSION_MISMATCH,
        /** 缺少签名 / 派生参数不完整 */
        MISSING_SIGNATURE,
        /** 去重集容量超限（拒绝新请求，防 OOM） */
        OVER_CAPACITY
    }

    /** 本地明文凭证（Worker 配置 schedule-job.accessToken） */
    private final String plainToken;

    /** 本地凭证版本（Worker 配置 schedule-job.credential-version，默认 1） */
    private final int localCredentialVersion;

    /**
     * 去重集：requestId -> 首次到达时间。仅 I/O 线程访问，并发安全由
     * ConcurrentHashMap 保证。
     */
    private final Map<String, Long> seenRequestIds = new ConcurrentHashMap<>();

    /**
     * 派生密钥缓存：{@code version:salt:iterations} -> 派生密钥（Base64）。
     * 命中后单次 HMAC，避免每个请求重复 PBKDF2 派生。
     */
    private final Map<String, String> derivedKeyCache = new ConcurrentHashMap<>();

    public RpcRequestAuthenticator(String plainToken, int localCredentialVersion) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new IllegalArgumentException("plainToken (schedule-job.accessToken) must not be blank");
        }
        this.plainToken = plainToken;
        this.localCredentialVersion = localCredentialVersion;
    }

    /**
     * I/O 线程轻量预检：时间窗 + 版本 + requestId 去重。
     * <b>不进行任何 PBKDF2 派生</b>（派生留在业务线程池的 {@link #authenticate}）。
     *
     * @return {@link AuthResult#OK} 表示通过预检；其余为拒绝原因
     */
    public AuthResult preCheck(ScheduleJobRequest request) {
        if (request.getTimestamp() == null
                || Math.abs(System.currentTimeMillis() - request.getTimestamp()) > TIME_WINDOW_MS) {
            return AuthResult.TIMESTAMP_EXPIRED;
        }
        if (request.getCredentialVersion() == null
                || request.getCredentialVersion() != localCredentialVersion) {
            return AuthResult.VERSION_MISMATCH;
        }
        if (request.getSignature() == null || request.getSignature().isBlank()
                || request.getSalt() == null || request.getIterations() == null) {
            return AuthResult.MISSING_SIGNATURE;
        }
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            return AuthResult.REPLAY;
        }
        if (seenRequestIds.size() >= MAX_TRACKED_REQUESTS) {
            long now = System.currentTimeMillis();
            // 先清过期条目；仍超限则拒绝新请求（防 OOM，宁可短暂拒合法流量）
            seenRequestIds.entrySet().removeIf(e -> now - e.getValue() > TRACK_TTL_MS);
            if (seenRequestIds.size() >= MAX_TRACKED_REQUESTS) {
                return AuthResult.OVER_CAPACITY;
            }
        }
        Long previous = seenRequestIds.putIfAbsent(request.getRequestId(), System.currentTimeMillis());
        if (previous != null) {
            return AuthResult.REPLAY;
        }
        return AuthResult.OK;
    }

    /**
     * 业务线程池验签：派生（或缓存命中）HMAC 密钥后常量时间比较签名。
     * 仅当 {@link #preCheck} 已通过时调用。
     *
     * @return true 表示签名匹配
     */
    public boolean authenticate(ScheduleJobRequest request) {
        String key = derivedKey(request);
        String canonical = HmacSha256Signer.canonical(
                request.getRequestId(), request.getJobname(), request.getExecuteParam(),
                request.getTimestamp(), request.getRequestId());
        return HmacSha256Signer.verify(key, canonical, request.getSignature());
    }

    /**
     * 按 (version, salt, iterations) 取派生密钥：缓存命中返回，未命中派生后写入。
     * 缓存满时按版本号淘汰（只保留版本号最大的 {@link #MAX_DERIVED_KEY_CACHE} 个）。
     */
    private String derivedKey(ScheduleJobRequest request) {
        String salt = request.getSalt();
        Integer iterations = request.getIterations();
        String cacheKey = request.getCredentialVersion() + ":" + salt + ":" + iterations;
        String cached = derivedKeyCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String derived = Pbkdf2Digest.derive(plainToken, salt, iterations);
        if (derivedKeyCache.size() >= MAX_DERIVED_KEY_CACHE) {
            // 淘汰最小版本号：轮换场景下新版本号更大，保留新版本天然正确
            String minKey = null;
            int minVersion = Integer.MAX_VALUE;
            for (String key : derivedKeyCache.keySet()) {
                int version = Integer.parseInt(key.substring(0, key.indexOf(':')));
                if (version < minVersion) {
                    minVersion = version;
                    minKey = key;
                }
            }
            if (minKey != null) {
                derivedKeyCache.remove(minKey);
            }
        }
        derivedKeyCache.put(cacheKey, derived);
        return derived;
    }

    /** 包私有：测试断言去重集规模 */
    int trackedRequestCount() {
        return seenRequestIds.size();
    }

    /** 包私有：测试断言派生缓存规模 */
    int derivedKeyCacheSize() {
        return derivedKeyCache.size();
    }
}
