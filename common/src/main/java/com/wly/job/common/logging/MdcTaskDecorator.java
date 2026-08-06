package com.wly.job.common.logging;

import org.slf4j.MDC;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 跨线程 MDC（Mapped Diagnostic Context）快照传递工具。
 * <p>
 * slf4j 的 {@link MDC} 基于 {@link ThreadLocal} 实现，不会随任务提交自动跨线程传播。
 * 本工具在父线程提交任务时捕获其 MDC 快照，在任务执行线程内恢复该快照，并在任务结束后
 * （{@code finally}）恢复任务执行前的快照（而非简单 {@code clear()}），从而保证复用线程
 * 不会被上一个任务残留的 MDC 值污染。
 * <p>
 * <b>虚拟线程兼容性</b>（Spec 2026-08-06 §2.3）：
 * <ul>
 *   <li>虚拟线程之间同样没有自动的 MDC 传播，本装饰器在虚拟线程模式下依然必要；</li>
 *   <li>"恢复快照"策略在平台线程池（防止复用污染）与虚拟线程（无复用场景）下均正确；</li>
 *   <li>未来虚拟线程化仅需切换线程工厂（如 {@code newVirtualThreadPerTaskExecutor}），
 *       本装饰器可原位复用，无需改动。</li>
 * </ul>
 * <p>
 * 纯 JDK + slf4j 实现，无 Spring 依赖，供调度链路（派发 worker / 回调 / Worker 业务执行线程池）统一包装使用。
 */
public final class MdcTaskDecorator {

    private MdcTaskDecorator() {
    }

    /**
     * 捕获当前线程（父线程）的 MDC 快照，返回包装后的任务。
     * <p>
     * 任务执行前恢复父线程快照（父线程无 MDC 时 {@code clear()}）；{@code finally} 中恢复
     * 任务执行前的快照（非 clear），避免复用线程被污染。
     *
     * @param task 原始任务，不可为 null
     * @return 包装后的任务
     */
    public static Runnable decorate(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (context == null || context.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                if (prev == null || prev.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(prev);
                }
            }
        };
    }

    /**
     * 将线程池包装为 MDC 自动传播的线程池（等价于 {@link MdcExecutorService#wrap(java.util.concurrent.ExecutorService)}）。
     *
     * @param delegate 底层线程池
     * @return 包装后的增强线程池
     */
    public static MdcExecutorService wrap(java.util.concurrent.ExecutorService delegate) {
        return MdcExecutorService.wrap(delegate);
    }

    /**
     * 与 {@link #decorate(Runnable)} 等价的 {@link Callable} 版本，供 {@code submit}/{@code invokeAll} 使用。
     */
    static <T> Callable<T> decorate(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (context == null || context.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                return task.call();
            } finally {
                if (prev == null || prev.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(prev);
                }
            }
        };
    }

    /** 批量包装 Callable（供 {@link MdcExecutorService#invokeAll} 使用），同包可见 */
    static <T> List<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(MdcTaskDecorator::decorate).toList();
    }
}
