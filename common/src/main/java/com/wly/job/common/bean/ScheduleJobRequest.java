package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调度 RPC 请求消息：Admin 将到期的 Job 派发至 Worker 时，经 Netty TCP 通道发送的请求体。
 * <p>
 * {@code requestId} 用于全链路追踪与响应回配，{@code executionId} 关联调度执行记录
 * （ScheduleRecord），{@code executeParam} 为传给执行方法参数的原始字符串。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobRequest {
    private String requestId;

    /**
     * job执行记录ID
     */
    private String executionId;

    private String jobname;

    private String executeParam;
}
