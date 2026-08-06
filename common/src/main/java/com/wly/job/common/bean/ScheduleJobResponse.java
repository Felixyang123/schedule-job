package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调度 RPC 响应消息：Worker 执行完 Job 后经 Netty TCP 通道回给 Admin 的执行结果体。
 * <p>
 * {@code success=true} 表示执行成功并携带 {@code result}；失败时置 {@code success=false}
 * 并将异常信息放入 {@code error}；{@code requestId} 与请求侧一一对应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobResponse {

    private String requestId;

    private Object result;

    private String error;

    private boolean success;
}