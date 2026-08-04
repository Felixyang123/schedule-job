package com.wly.job.core.bean;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;

import java.util.Optional;

/**
 * 作业执行上下文：基于 ThreadLocal 暴露当前调度请求/响应，供被调用的作业方法
 * 在业务线程内读取 executeParam 等上下文信息，避免显式传参。
 * <p>
 * 注意：由 {@link com.wly.job.core.invocation.MethodInvocationJob} 在执行前后设置与清理，
 * 必须与业务执行同线程配对使用，防止线程池复用造成上下文串扰。
 */
public class ExecuteJobContext {
    private static final ThreadLocal<ScheduleJobRequest> REQUEST = new ThreadLocal<>();

    private static final ThreadLocal<ScheduleJobResponse> RESPONSE = new ThreadLocal<>();

    public static void setRequest(ScheduleJobRequest request) {
        REQUEST.set(request);
    }

    public static ScheduleJobRequest getRequest() {
        return REQUEST.get();
    }

    public static void setResponse(ScheduleJobResponse response) {
        RESPONSE.set(response);
    }

    public static ScheduleJobResponse getResponse() {
        return RESPONSE.get();
    }

    public static void clear() {
        REQUEST.remove();
        RESPONSE.remove();
    }

    public static String getExecuteParam() {
        return Optional.ofNullable(getRequest()).map(ScheduleJobRequest::getExecuteParam).orElse(null);
    }
}
