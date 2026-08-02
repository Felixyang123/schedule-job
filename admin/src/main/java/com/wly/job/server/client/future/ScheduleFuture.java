package com.wly.job.server.client.future;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.callback.ScheduleCallback;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC异步结果Future：完成后在专用执行器上异步派发回调（异常隔离）。
 */
@Slf4j
public class ScheduleFuture<T> extends CompletableFuture<T> {

    private static final ExecutorService CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "schedule-future-callback");
        thread.setDaemon(true);
        return thread;
    });

    private final long createTime;

    @Setter
    private long timeout;

    @Setter
    @Getter
    private Channel channel;

    private final List<CallbackEntry> callbacks = new CopyOnWriteArrayList<>();

    public ScheduleFuture(long timeout, Channel channel) {
        this.createTime = System.currentTimeMillis();
        this.timeout = timeout;
        this.channel = channel;
    }

    public void addCallback(ScheduleCallback callback, ScheduleCallbackContext context) {
        callbacks.add(new CallbackEntry(callback, context));
    }

    @Override
    public boolean complete(T value) {
        boolean done = super.complete(value);
        if (done) {
            dispatchCallbacks(value, null);
        }
        return done;
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        boolean done = super.completeExceptionally(ex);
        if (done) {
            dispatchCallbacks(null, ex);
        }
        return done;
    }

    private void dispatchCallbacks(T value, Throwable ex) {
        try {
            CALLBACK_EXECUTOR.execute(() -> {
                for (CallbackEntry entry : callbacks) {
                    try {
                        if (ex == null) {
                            entry.callback().onSuccess(entry.context(), value);
                        } else {
                            entry.callback().onFailure(entry.context(), ex);
                        }
                    } catch (Exception e) {
                        log.error("Schedule callback fail, requestId: {}",
                                entry.context().request().getRequestId(), e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Callback executor has been shut down, callbacks skipped.");
        }
    }

    public static void shutdown() {
        CALLBACK_EXECUTOR.shutdown();
        try {
            if (!CALLBACK_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                CALLBACK_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            CALLBACK_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record CallbackEntry(ScheduleCallback callback, ScheduleCallbackContext context) {
    }

    /**
     * 获取结果，支持超时
     */
    @Override
    public T get() {
        try {
            return super.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScheduleException("Schedule interrupted", e);
        } catch (TimeoutException e) {
            throw new ScheduleException("Schedule timeout", e);
        } catch (ExecutionException e) {
            throw new ScheduleException("Schedule error", e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * 获取结果，指定超时时间
     */
    public T get(long timeout, TimeUnit unit) {
        try {
            return super.get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScheduleException("Schedule interrupted", e);
        } catch (TimeoutException e) {
            throw new ScheduleException("Schedule timeout", e);
        } catch (ExecutionException e) {
            throw new ScheduleException("Schedule error", e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * 检查是否已超时
     */
    public boolean isTimeout() {
        return System.currentTimeMillis() - createTime > timeout;
    }
}
