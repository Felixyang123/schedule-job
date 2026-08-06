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
 * 调度执行 ID（每次调度唯一，与 {@code schedule_rec} 关联），{@code executionId} 关联调度
 * 执行记录，{@code executeParam} 为传给执行方法参数的原始字符串。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobRequest {

    /** 链路追踪 ID：贯穿整个请求/调度链路（HTTP 层 R1 或 cron 链路起点），不变 */
    private String traceId;

    /** 调度执行 ID：每次调度唯一（R2），与 schedule_rec 关联 */
    private String requestId;

    /**
     * job执行记录ID
     */
    private String executionId;

    private String jobname;

    private String executeParam;

    /**
     * RPC 鉴权令牌：Admin 派发时携带 {@code schedule.access-token}，Worker 侧与本地
     * {@code schedule-job.accessToken} 一致才执行，防止伪造请求触发任意已注册任务。
     * 旧 Admin / 未配置 token 时序列化为 null，Worker 未配置 expectedToken 则跳过校验。
     */
    private String token;
}
