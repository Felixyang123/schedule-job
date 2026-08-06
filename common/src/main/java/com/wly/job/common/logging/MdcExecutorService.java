package com.wly.job.common.logging;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MDC 自动传播的执行器服务（增强线程池）。
 * <p>
 * 封装一个 {@link ExecutorService}：所有 {@code execute}/{@code submit}/{@code invokeAll} 提交的任务
 * 在进入线程池前统一套用 {@link MdcTaskDecorator#decorate(Runnable)} / {@link MdcTaskDecorator#decorate(Callable)}
 * —— 捕获提交线程的 MDC 快照、在任务执行线程内恢复、任务结束后还原，从而让 requestId 等上下文
 * 自动跨线程透传。业务调用方只需用 {@link #wrap(ExecutorService)} 获得增强线程池后直接提交，
 * 无需在调用点手动包装。
 * <p>
 * <b>注入职责边界</b>（Spec 2026-08-06 §2.3）：本类只负责"已有 MDC 的跨线程透传"；requestId 的
 * <b>注入</b>统一发生在流量/任务入口（HTTP 的 {@code RequestLogFilter}、调度派发线程、Worker
 * 网络入口、回调入口），业务同步代码不做任何 MDC 操作。
 * <p>
 * <b>虚拟线程兼容性</b>：虚拟线程之间同样无自动 MDC 传播，本包装在虚拟线程模式下依然必要；
 * 未来虚拟线程化仅需切换底层线程工厂，本类可原位复用。
 *
 * @see MdcTaskDecorator
 */
public final class MdcExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    private MdcExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    /**
     * 将普通线程池包装为 MDC 自动传播的增强线程池。
     *
     * @param delegate 底层线程池，不可为 null
     * @return 增强线程池
     */
    public static MdcExecutorService wrap(ExecutorService delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        return new MdcExecutorService(delegate);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(MdcTaskDecorator.decorate(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(MdcTaskDecorator.decorate(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(MdcTaskDecorator.decorate(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(MdcTaskDecorator.decorate(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(MdcTaskDecorator.wrapCallables(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(MdcTaskDecorator.wrapCallables(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        // invokeAny 不做 MDC 传播（多任务竞争语义下无明确单一父上下文），直接透传
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
