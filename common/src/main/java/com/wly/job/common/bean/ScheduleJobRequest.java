package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调度 RPC 请求消息：Admin 将到期的 Job 派发至 Worker 时，经 Netty TCP 通道发送的请求体。
 * <p>
 * {@code traceId} 为链路追踪 ID（HTTP 层 X-Request-Id 或 cron 调度链路的起点值），
 * <b>贯穿整个请求/调度链路不变</b>，Worker 执行与回调日志据此聚合；{@code requestId} 为
 * 调度执行 ID（每次调度唯一，与 {@code schedule_rec} 关联），{@code executeParam} 为传给
 * 执行方法参数的原始字符串。
 *
 * <p>RPC 鉴权（ADR-0006 / Spec 2026-08-11 §3.2）：本请求<b>不携带任何可复用凭证</b>，
 * 以凭证摘要（token_hash）为 HMAC-SHA256 密钥对规范化请求签名；验签参数
 * {@code credentialVersion / salt / iterations / timestamp / signature} 随请求携带，
 * {@code nonce} 复用 {@code requestId}。Worker 用本地明文按 salt/iterations 重新派生
 * 同一密钥验签，并按 ±30s 时间窗 + requestId 去重集拒绝重放。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobRequest {

    /** 链路追踪 ID：贯穿整个请求/调度链路（HTTP 层 R1 或 cron 链路起点），不变 */
    private String traceId;

    /** 调度执行 ID：每次调度唯一（R2），与 schedule_rec 关联，兼作签名 nonce */
    private String requestId;

    private String jobname;

    private String executeParam;

    /** 派发所用凭证版本号（Admin 按实例身份查 active 版本写入） */
    private Integer credentialVersion;

    /** 凭证派生盐（Base64，来自凭证版本行） */
    private String salt;

    /** PBKDF2 迭代次数 */
    private Integer iterations;

    /** 签名时间戳（毫秒，Worker 按 ±30s 时间窗校验） */
    private Long timestamp;

    /** HMAC-SHA256 请求签名（Base64，密钥为 PBKDF2 派生的凭证摘要） */
    private String signature;
}
