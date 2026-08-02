package com.wly.job.server.client.callback;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.future.ScheduleFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回调注册表：Spring 自动收集所有 {@link ScheduleCallback} Bean，按 @Order 排序后附加到每个请求的 Future。
 */
@Component
@RequiredArgsConstructor
public class ScheduleCallableRegistry {

    private final List<ScheduleCallback> callbacks;

    public void attachAll(ScheduleFuture<ScheduleJobResponse> future, ScheduleCallbackContext context) {
        callbacks.forEach(callback -> future.addCallback(callback, context));
    }
}
