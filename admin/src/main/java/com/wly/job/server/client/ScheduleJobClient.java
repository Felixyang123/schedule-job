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
 * <p>可观测性（Spec §2.7）：双计时点——派发延迟（入口到 Netty 写包完成）与请求响应延迟
 * （入口到 Future 首次完成，含 Worker 执行时间），两者相减可定位瓶颈所在侧。
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
        // 双计时点，两段区间相减即可定位瓶颈在 Admin 派发侧还是 Worker 执行侧：
        // dispatch = 入口 -> Netty 写包完成（建连失败不停表，由 callback.failure 计数兜底）
        // request  = 入口 -> Future 首次完成（响应/建连失败/写失败/超时/断链均停表）
        // whenComplete 由 CompletableFuture 完成语义保证仅回调一次，重复完成不会重复计数。
        Timer.Sample dispatchSample = Timer.start();
        Timer.Sample requestSample = Timer.start();
        ScheduleFuture<ScheduleJobResponse> future =
                new ScheduleFuture<>(props.getReqTimeout(), null, callbackExecutor,
                        request.getTraceId(), request.getRequestId());
        future.whenComplete((response, throwable) ->
                requestSample.stop(metrics.timer(MetricsRegistry.JOB_REQUEST_LATENCY)));
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
                if (f.isSuccess()) {
                    dispatchSample.stop(metrics.timer(MetricsRegistry.JOB_DISPATCH_LATENCY));
                    log.debug("Send schedule request success, requestId: {}, worker: {}:{}",
                            requestId, instance.getHost(), instance.getPort());
                } else {
                    log.error("Send schedule request fail, requestId: {}, worker: {}:{}",
                            requestId, instance.getHost(), instance.getPort(), f.cause());
                    // 写包失败通常意味着连接已半开（对端网络分区/宕机但本地未收到 FIN，
                    // channelInactive 不会触发）：主动摘除缓存，避免后续请求反复复用同一僵尸连接，
                    // 下次派发会触发重新建连。
                    channelManager.removeChannel(channel);
                    // 异步写失败必须异常完成 Future：ScheduleRecCallback.onFailure 负责标 FAIL；
                    // 单次任务同时释放 in-flight 并写 REQUEUE 变更记录，闭合 At-Least-Once 重试。
                    requestHandler.completeExceptionally(
                            requestId, new ScheduleException("Send request fail", f.cause()));
                }
            });
        });
    }
}
