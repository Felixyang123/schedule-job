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
import com.wly.job.server.metrics.MetricsRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * Admin 侧调度 RPC 客户端（record）：把一次派发封装为
 * "创建 Future -> 挂载全部回调 -> 建立/复用执行器连接 -> 注册 requestId 映射 -> 异步写包发送"。
 * 发送失败/建连失败统一走 Future 的异常完成，由回调统一处理失败重试，不向调用线程外泄。
 * <p>可观测性（Spec §2.7）：记录派发延迟（send() 入口到 Netty 写包成功路径）。
 */
@Component
@Slf4j
public record ScheduleJobClient(ScheduleProps props,
                                ScheduleCallableRegistry callbackRegistry,
                                ChannelManager channelManager,
                                ScheduleRequestHandler requestHandler,
                                ExecutorService callbackExecutor,
                                MetricsRegistry metrics) {

    /**
     * 异步派发一次执行到指定执行器。
     *
     * @param singleRun 是否为单次任务（影响回调中 Finished/in-flight 的处理语义）
     */
    public void send(ScheduleJobRequest request, JobInstance instance, Long jobId, boolean singleRun) {
        // 派发延迟计时：入口起表，成功写包路径停表（建连失败不记录，由 callback.failure 计数兜底）
        Timer.Sample sample = Timer.start();
        ScheduleFuture<ScheduleJobResponse> future =
                new ScheduleFuture<>(props.getReqTimeout(), null, callbackExecutor);
        String requestId = request.getRequestId();
        callbackRegistry.attachAll(future, new ScheduleCallbackContext(request, jobId, singleRun));
        channelManager.getChannelAsync(instance.getHost(), instance.getPort()).whenComplete((channel, throwable) -> {
            if (throwable != null) {
                log.error("Connect schedule instance fail: {}:{}", instance.getHost(), instance.getPort(), throwable);
                future.completeExceptionally(new ScheduleException(
                        "Connect schedule instance fail: " + instance.getHost() + ":" + instance.getPort(), throwable));
                return;
            }
            future.setChannel(channel);
            // 先注册 requestId -> Future 映射再写包，保证响应到达前映射已就绪
            requestHandler.put(requestId, future, props.getReqTimeout());
            channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Send request fail: ", f.cause());
                    requestHandler.completeExceptionally(
                            requestId, new ScheduleException("Send request fail", f.cause()));
                }
            });
            sample.stop(metrics.timer(MetricsRegistry.JOB_DISPATCH_LATENCY));
        });
    }
}
