package com.wly.job.server.client.future;

public interface ScheduleCallable {

    void onSuccess(Object result);

    void onFailure(Throwable throwable);
}
