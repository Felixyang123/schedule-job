package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.future.ScheduleCallable;
import com.wly.job.server.client.future.ScheduleFuture;
import io.netty.channel.Channel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * RPC请求处理器，用于管理请求ID与Future的映射关系
 */
@Slf4j
public class ScheduleRequestHandler {

    private static final Map<String, ScheduleFuture<ScheduleJobResponse>> REQUEST_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> CHANNEL_REQ_IDS_MAP = new ConcurrentHashMap<>();

    // 超时时间监控映射
    private static final Map<String, Long> TIMEOUT_MAP = new ConcurrentHashMap<>();

    // 默认超时时间（毫秒）
    private static final long DEFAULT_TIMEOUT = 5000;

    // 清理过期请求的线程
    private static volatile boolean cleanupThreadStarted = false;
    private static final Object LOCK = new Object();

    @Setter
    private static ExecutorService CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor();

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

        // 启动清理线程（懒加载）
        startCleanupThreadIfNeeded();
    }

    private static String channelId(ScheduleFuture<ScheduleJobResponse> future) {
        return Optional.ofNullable(future.getChannel()).map(channel -> channel.id().asLongText()).orElse(null);
    }

    /**
     * 完成请求，触发Future的完成
     */
    public static void complete(String requestId, ScheduleJobResponse response) {
        ScheduleFuture<ScheduleJobResponse> future = REQUEST_MAP.remove(requestId);
        TIMEOUT_MAP.remove(requestId);

        if (future != null) {
            removeReqId(requestId, future);
            if (response.isSuccess()) {
                future.complete(response);
                callbackOnSuccess(future.getCallables(), response);
            } else {
                ScheduleException scheduleException = new ScheduleException("Schedule job fail: " + response.getError());
                future.completeExceptionally(scheduleException);
                callbackOnFailure(future.getCallables(), scheduleException);
            }
        } else {
            log.error("No schedule future found: {}", requestId);
        }
    }

    private static void callbackOnSuccess(List<ScheduleCallable> callables, ScheduleJobResponse response) {
        CALLBACK_EXECUTOR.submit(() -> callables.forEach(callable -> callable.onSuccess(response.getResult())));
    }

    private static void callbackOnFailure(List<ScheduleCallable> callables, ScheduleException scheduleException) {
        CALLBACK_EXECUTOR.submit(() -> callables.forEach(callable -> callable.onFailure(scheduleException)));
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

    /**
     * 启动清理过期请求的线程
     */
    private static void startCleanupThreadIfNeeded() {
        if (!cleanupThreadStarted) {
            synchronized (LOCK) {
                if (!cleanupThreadStarted) {
                    Thread cleanupThread = new Thread(new TimeoutCleanupTask(), "ScheduleRequestHandler-Cleanup");
                    cleanupThread.setDaemon(true);
                    cleanupThread.start();
                    cleanupThreadStarted = true;
                }
            }
        }
    }

    /**
     * 清理过期请求的任务
     */
    private static class TimeoutCleanupTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 每30秒清理一次过期请求
                    TimeUnit.SECONDS.sleep(30);
                    cleanupExpiredRequests();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Clear up timeout schedule job fail: ", e);
                }
            }
        }

        private void cleanupExpiredRequests() {
            long currentTime = System.currentTimeMillis();
            int cleanedCount = 0;

            for (Map.Entry<String, Long> entry : TIMEOUT_MAP.entrySet()) {
                String requestId = entry.getKey();
                Long expireTime = entry.getValue();

                if (expireTime != null && currentTime > expireTime) {
                    ScheduleFuture<ScheduleJobResponse> future = remove(requestId);
                    if (future != null) {
                        removeReqId(requestId, future);
                        future.completeExceptionally(
                                new ScheduleException("Schedule timeout, requestId: " + requestId + ", timeout: " + (currentTime - expireTime) + "ms")
                        );
                        cleanedCount++;
                    }
                }
            }

            if (cleanedCount > 0) {
                log.info("Clear up timeout schedule jobs count: {}", cleanedCount);
            }
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
                    future.completeExceptionally(new ScheduleException("Connection lost"));
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
}