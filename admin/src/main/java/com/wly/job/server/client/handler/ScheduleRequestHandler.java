package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.future.ScheduleFuture;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RPC请求处理器，用于管理请求ID与Future的映射关系。
 * 回调派发由 {@link ScheduleFuture} 在完成后统一触发，本类只负责完成 Future。
 */
@Slf4j
public class ScheduleRequestHandler {

    private static final Map<String, ScheduleFuture<ScheduleJobResponse>> REQUEST_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> CHANNEL_REQ_IDS_MAP = new ConcurrentHashMap<>();

    // 超时时间监控映射
    private static final Map<String, Long> TIMEOUT_MAP = new ConcurrentHashMap<>();

    // 默认超时时间（毫秒）
    private static final long DEFAULT_TIMEOUT = 5000;

    // 超时清理调度线程池（单线程、固定延迟）
    private static final ScheduledExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ScheduleRequestHandler-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean(false);

    /**
     * 注册请求Future
     */
    public static void put(String requestId, ScheduleFuture<ScheduleJobResponse> future) {
        put(requestId, future, DEFAULT_TIMEOUT);
    }

    /**
     * 注册请求Future，指定超时时间
     */
    public static void put(String requestId, ScheduleFuture<ScheduleJobResponse> future, long timeout) {
        REQUEST_MAP.put(requestId, future);
        TIMEOUT_MAP.put(requestId, System.currentTimeMillis() + timeout);
        Optional.ofNullable(channelId(future)).ifPresent(channelId ->
                CHANNEL_REQ_IDS_MAP.computeIfAbsent(channelId, k -> new CopyOnWriteArraySet<>()).add(requestId));

        startCleanupIfNeeded();
    }

    private static String channelId(ScheduleFuture<ScheduleJobResponse> future) {
        return Optional.ofNullable(future.getChannel()).map(channel -> channel.id().asLongText()).orElse(null);
    }

    /**
     * 完成请求，触发Future的完成（回调由 ScheduleFuture 异步派发）
     */
    public static void complete(String requestId, ScheduleJobResponse response) {
        ScheduleFuture<ScheduleJobResponse> future = REQUEST_MAP.remove(requestId);
        TIMEOUT_MAP.remove(requestId);

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
    public static void completeExceptionally(String requestId, Throwable cause) {
        ScheduleFuture<ScheduleJobResponse> future = REQUEST_MAP.remove(requestId);
        TIMEOUT_MAP.remove(requestId);
        if (future != null) {
            removeReqId(requestId, future);
            future.completeExceptionally(cause);
        }
    }

    private static void removeReqId(String requestId, ScheduleFuture<ScheduleJobResponse> future) {
        Optional.ofNullable(channelId(future)).ifPresent(channelId -> CHANNEL_REQ_IDS_MAP.computeIfPresent(channelId,
                (k, reqIds) -> {
                    reqIds.remove(requestId);
                    return reqIds;
                }));
    }

    /**
     * 获取请求Future
     */
    public static ScheduleFuture<ScheduleJobResponse> get(String requestId) {
        return REQUEST_MAP.get(requestId);
    }

    /**
     * 移除请求（超时或取消时调用）
     */
    public static ScheduleFuture<ScheduleJobResponse> remove(String requestId) {
        TIMEOUT_MAP.remove(requestId);
        return REQUEST_MAP.remove(requestId);
    }

    /**
     * 检查请求是否已超时
     */
    public static boolean isTimeout(String requestId) {
        Long expireTime = TIMEOUT_MAP.get(requestId);
        if (expireTime == null) {
            return true; // 如果不存在，认为已超时
        }
        return System.currentTimeMillis() > expireTime;
    }

    /**
     * 获取所有未完成的请求数量
     */
    public static int getPendingRequestCount() {
        return REQUEST_MAP.size();
    }

    private static void startCleanupIfNeeded() {
        if (CLEANUP_STARTED.compareAndSet(false, true)) {
            CLEANUP_EXECUTOR.scheduleWithFixedDelay(
                    ScheduleRequestHandler::cleanupExpiredRequests, 30, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * 清理过期请求（包内可见，供测试直接调用）
     */
    static void cleanupExpiredRequests() {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;

        for (Map.Entry<String, Long> entry : TIMEOUT_MAP.entrySet()) {
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
     * 清理所有未完成的请求（连接断开时调用）
     */
    public static void cleanupAllRequests(Channel channel) {
        String channelId = channel.id().asLongText();
        CHANNEL_REQ_IDS_MAP.computeIfPresent(channelId, (k, requestIds) -> {
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
     * 获取超时映射的副本（用于监控）
     */
    public static Map<String, Long> getTimeoutMapSnapshot() {
        return new ConcurrentHashMap<>(TIMEOUT_MAP);
    }

    /**
     * 获取请求映射的副本（用于监控）
     */
    public static Map<String, ScheduleFuture<ScheduleJobResponse>> getRequestMapSnapshot() {
        return new ConcurrentHashMap<>(REQUEST_MAP);
    }

    /**
     * 优雅关闭：停止超时清理调度器，并等待在途回调完成（有界 3 秒）
     */
    public static void shutdown() {
        CLEANUP_EXECUTOR.shutdownNow();
        ScheduleFuture.shutdown();
    }
}
