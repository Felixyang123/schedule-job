package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.future.ScheduleFuture;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RPC请求处理器（Spring Bean），用于管理请求ID与Future的映射关系。
 * 回调派发由 {@link ScheduleFuture} 在完成后统一触发，本类只负责完成 Future。
 * 实例化后每个上下文持有独立的 Map 与超时清理线程，容器重启时清理线程随 Bean 重建，不再残留。
 */
@Slf4j
@Component
public class ScheduleRequestHandler {

    private final Map<String, ScheduleFuture<ScheduleJobResponse>> requestMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> channelReqIdsMap = new ConcurrentHashMap<>();

    // 超时时间监控映射
    private final Map<String, Long> timeoutMap = new ConcurrentHashMap<>();

    // 默认超时时间（毫秒）
    private static final long DEFAULT_TIMEOUT = 5000;

    // 超时清理调度线程池（单线程、固定延迟，惰性启动）
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ScheduleRequestHandler-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);

    /**
     * 注册请求Future
     */
    public void put(String requestId, ScheduleFuture<ScheduleJobResponse> future) {
        put(requestId, future, DEFAULT_TIMEOUT);
    }

    /**
     * 注册请求Future，指定超时时间
     */
    public void put(String requestId, ScheduleFuture<ScheduleJobResponse> future, long timeout) {
        requestMap.put(requestId, future);
        timeoutMap.put(requestId, System.currentTimeMillis() + timeout);
        Optional.ofNullable(channelId(future)).ifPresent(channelId ->
                channelReqIdsMap.computeIfAbsent(channelId, k -> new CopyOnWriteArraySet<>()).add(requestId));

        startCleanupIfNeeded();
    }

    private String channelId(ScheduleFuture<ScheduleJobResponse> future) {
        return Optional.ofNullable(future.getChannel()).map(channel -> channel.id().asLongText()).orElse(null);
    }

    /**
     * 完成请求，触发Future的完成（回调由 ScheduleFuture 异步派发）
     */
    public void complete(String requestId, ScheduleJobResponse response) {
        ScheduleFuture<ScheduleJobResponse> future = requestMap.remove(requestId);
        timeoutMap.remove(requestId);

        if (future != null) {
            removeReqId(requestId, future);
            if (response.isSuccess()) {
                future.complete(response);
            } else {
                future.completeExceptionally(new ScheduleException("Schedule job fail: " + response.getError()));
            }
        } else {
            log.error("No schedule future found: {}", requestId);
        }
    }

    /**
     * 移除并异常完成请求（发送失败等场景）
     */
    public void completeExceptionally(String requestId, Throwable cause) {
        ScheduleFuture<ScheduleJobResponse> future = requestMap.remove(requestId);
        timeoutMap.remove(requestId);
        if (future != null) {
            removeReqId(requestId, future);
            future.completeExceptionally(cause);
        }
    }

    private void removeReqId(String requestId, ScheduleFuture<ScheduleJobResponse> future) {
        Optional.ofNullable(channelId(future)).ifPresent(channelId -> channelReqIdsMap.computeIfPresent(channelId,
                (k, reqIds) -> {
                    reqIds.remove(requestId);
                    return reqIds;
                }));
    }

    /**
     * 移除请求（超时或取消时调用）
     */
    public ScheduleFuture<ScheduleJobResponse> remove(String requestId) {
        timeoutMap.remove(requestId);
        return requestMap.remove(requestId);
    }

    private void startCleanupIfNeeded() {
        if (cleanupStarted.compareAndSet(false, true)) {
            cleanupExecutor.scheduleWithFixedDelay(
                    this::cleanupExpiredRequests, 30, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * 清理过期请求（包内可见，供测试直接调用）：
     * 遍历超时映射，把已超时的请求移除并以"调度超时"异常完成其 Future，由其回调统一触发失败重试。
     */
    void cleanupExpiredRequests() {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;

        for (Map.Entry<String, Long> entry : timeoutMap.entrySet()) {
            String requestId = entry.getKey();
            Long expireTime = entry.getValue();

            if (expireTime != null && currentTime > expireTime) {
                ScheduleFuture<ScheduleJobResponse> future = remove(requestId);
                if (future != null) {
                    removeReqId(requestId, future);
                    future.completeExceptionally(new ScheduleException(
                            "Schedule timeout, requestId: " + requestId
                                    + ", timeout: " + (currentTime - expireTime) + "ms"));
                    cleanedCount++;
                }
            }
        }

        if (cleanedCount > 0) {
            log.info("Clear up timeout schedule jobs count: {}", cleanedCount);
        }
    }

    /**
     * 清理所有未完成的请求（连接断开时调用）：
     * 通过 channelId -> requestId 集合反向索引，仅失败该连接上在途的请求，不影响其他连接。
     */
    public void cleanupAllRequests(Channel channel) {
        String channelId = channel.id().asLongText();
        channelReqIdsMap.computeIfPresent(channelId, (k, requestIds) -> {
            for (String requestId : requestIds) {
                ScheduleFuture<ScheduleJobResponse> future = remove(requestId);
                if (future != null) {
                    future.completeExceptionally(new ScheduleException("Connection lost, requestId: " + requestId));
                }
            }
            requestIds.clear();
            return requestIds;
        });
    }

    /**
     * 优雅关闭：停止超时清理调度器并清空全部映射（应用停止时由 NettyLifecycle 调用）。
     * 回调线程池由 NettyLifecycle 统一关闭，不在此处处理。
     */
    public void shutdown() {
        cleanupExecutor.shutdownNow();
        requestMap.clear();
        timeoutMap.clear();
        channelReqIdsMap.clear();
    }
}
