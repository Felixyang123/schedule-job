package com.wly.job.server.client.future;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.callback.ScheduleCallback;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC异步结果Future：完成后在注入的有界回调线程池上异步派发回调（异常隔离）。
 */
@Slf4j
public class ScheduleFuture<T> extends CompletableFuture<T> {

    /** 默认超时时间（毫秒），供阻塞式 {@link #get()} 使用 */
    private final long timeout;

    @Setter
    @Getter
    private Channel channel;

    private final ExecutorService callbackExecutor;

    private final List<CallbackEntry> callbacks = new CopyOnWriteArrayList<>();

    /** 回调链路 ID 在创建 Future 时从派发请求固化，避免依赖可变 callback 列表反向取上下文 */
    private final String traceId;

    private final String requestId;

    /**
     * 仅测试用简化构造器：traceId/requestId 为 null，回调日志不携带链路 ID。
     * 生产调用必须走 5 参构造器固化链路 ID（见 {@link #ScheduleFuture(long, Channel, ExecutorService, String, String)}）。
     */
    @Deprecated
    public ScheduleFuture(long timeout, Channel channel, ExecutorService callbackExecutor) {
        this(timeout, channel, callbackExecutor, null, null);
    }

    public ScheduleFuture(long timeout, Channel channel, ExecutorService callbackExecutor,
                          String traceId, String requestId) {
        this.timeout = timeout;
        this.channel = channel;
        this.callbackExecutor = callbackExecutor;
        this.traceId = traceId;
        this.requestId = requestId;
    }

    public void addCallback(ScheduleCallback callback, ScheduleCallbackContext context) {
        callbacks.add(new CallbackEntry(callback, context));
    }

    /**
     * 以成功结果完成 Future；首次完成时异步派发全部成功回调。
     * 返回 false 表示此前已完成（回调不重复派发）。
     */
    @Override
    public boolean complete(T value) {
        boolean done = super.complete(value);
        if (done) {
            dispatchCallbacks(value, null);
        }
        return done;
    }

    /**
     * 以异常完成 Future；首次完成时异步派发全部失败回调（返回 false 表示此前已完成）。
     */
    @Override
    public boolean completeExceptionally(Throwable ex) {
        boolean done = super.completeExceptionally(ex);
        if (done) {
            dispatchCallbacks(null, ex);
        }
        return done;
    }

    /**
     * 在注入的有界回调线程池上派发回调：与 Netty I/O 线程解耦，
     * 单个回调异常被捕获隔离，不影响其余回调与其他请求。
     * 队列满（或线程池已关闭）时捕获 {@link RejectedExecutionException} 降级为当前线程直接执行，
     * 保证回调不丢失（At-Least-Once 闭环关键步骤）。
     */
    private void dispatchCallbacks(T value, Throwable ex) {
        Runnable task = () -> {
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
        };
        // 回调入口注入（本方法可能在 Admin Netty I/O 线程执行，MDC 为空；traceId/requestId
        // 已在 Future 创建时从派发请求固化。callbackExecutor 由 MdcExecutorService 包装，
        // 提交时捕获此快照并在回调线程执行后恢复；finally 清理 I/O 线程，防止复用污染）。
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        if (requestId != null) {
            MDC.put("requestId", requestId);
        }
        try {
            callbackExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("Callback executor rejected task, run callbacks inline: {}", e.getMessage());
            task.run();
        } finally {
            if (traceId != null) {
                MDC.remove("traceId");
            }
            if (requestId != null) {
                MDC.remove("requestId");
            }
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
}
