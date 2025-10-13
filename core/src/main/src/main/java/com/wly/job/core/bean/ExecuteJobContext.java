package com.wly.job.core.bean;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;

import java.util.Optional;

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
