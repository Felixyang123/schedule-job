package com.wly.job.server.client.callback;

import com.wly.job.common.bean.ScheduleJobRequest;

/**
 * 回调上下文：请求体 + 作业维度信息，供监控/审计等扩展回调使用。
 */
public record ScheduleCallbackContext(ScheduleJobRequest request, Long jobId, boolean singleRun) {
}
