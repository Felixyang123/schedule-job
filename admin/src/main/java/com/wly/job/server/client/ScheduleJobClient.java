package com.wly.job.server.client;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import com.wly.job.server.client.callback.ScheduleCallableRegistry;
import com.wly.job.server.client.future.ScheduleFuture;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import com.wly.job.server.config.ScheduleProps;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public record ScheduleJobClient(ScheduleProps props, ScheduleCallableRegistry callbackRegistry) {

    public void send(ScheduleJobRequest request, JobInstance instance, Long jobId, boolean singleRun) {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(props.getReqTimeout(), null);
        String requestId = request.getRequestId();
        callbackRegistry.attachAll(future, new ScheduleCallbackContext(request, jobId, singleRun));
        ChannelManager.getChannelAsync(instance.getHost(), instance.getPort()).whenComplete((channel, throwable) -> {
            if (throwable != null) {
                log.error("Connect schedule instance fail: {}:{}", instance.getHost(), instance.getPort(), throwable);
                future.completeExceptionally(new ScheduleException(
                        "Connect schedule instance fail: " + instance.getHost() + ":" + instance.getPort(), throwable));
                return;
            }
            future.setChannel(channel);
            ScheduleRequestHandler.put(requestId, future, props.getReqTimeout());
            channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Send request fail: ", f.cause());
                    ScheduleRequestHandler.completeExceptionally(
                            requestId, new ScheduleException("Send request fail", f.cause()));
                }
            });
        });
    }
}
