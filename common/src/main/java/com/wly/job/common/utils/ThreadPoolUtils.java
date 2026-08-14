package com.wly.job.common.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程池生命周期工具：统一"优雅停机"模板（AGENTS.md §5.2 约束）。
 * <p>
 * 模式：先 {@code shutdown()} 停止接收新任务，有界等待在途任务完成；超时未完成则
 * {@code shutdownNow()} 强制中断；等待期间被中断时同样强制中断并复位中断标志。
 * 各 SmartLifecycle 组件停机时须先置 {@code running=false} 让循环线程自行退出，
 * 再调用本方法关闭线程池，防止 {@code awaitTermination} 无限等待或线程残留。
 * <p>
 * 可观测性：停机的三条非正常路径（等待超时被强制中断、等待期间线程被中断）都会输出
 * WARN 日志并带上线程池名与被丢弃的任务数，便于定位"停机时任务被打断"类问题；
 * 正常停机只输出 DEBUG。调用方应优先使用带 {@code poolName} 的重载以获得可定位的日志。
 */
@Slf4j
public final class ThreadPoolUtils {

    /** 调用方未提供线程池名时的占位名 */
    private static final String UNNAMED_POOL = "unnamed";

    private ThreadPoolUtils() {
    }

    /**
     * 优雅关闭线程池（空引用安全），日志中线程池名为 {@code unnamed}。
     *
     * @param executor 待关闭线程池，可为 null
     * @param timeout  有界等待时长
     * @param unit     等待时长单位
     */
    public static void shutdownGracefully(ExecutorService executor, long timeout, TimeUnit unit) {
        shutdownGracefully(executor, UNNAMED_POOL, timeout, unit);
    }

    /**
     * 优雅关闭线程池（空引用安全），并在日志中标注线程池名以便定位。
     *
     * @param executor 待关闭线程池，可为 null
     * @param poolName 线程池名（用于日志定位），为空时回退为 {@code unnamed}
     * @param timeout  有界等待时长
     * @param unit     等待时长单位
     */
    public static void shutdownGracefully(ExecutorService executor, String poolName, long timeout, TimeUnit unit) {
        String name = (poolName == null || poolName.isBlank()) ? UNNAMED_POOL : poolName;
        if (executor == null || executor.isShutdown()) {
            log.debug("Thread pool [{}] already shutdown or absent, skip.", name);
            return;
        }
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout, unit)) {
                log.debug("Thread pool [{}] terminated gracefully.", name);
                return;
            }
            // 超时未完成：强制中断在途任务，丢弃仍在队列中未开始的任务
            List<Runnable> dropped = executor.shutdownNow();
            log.warn("Thread pool [{}] did not terminate within {} {}, forced shutdown, dropped {} pending task(s).",
                    name, timeout, unit.name().toLowerCase(), dropped.size());
        } catch (InterruptedException e) {
            List<Runnable> dropped = executor.shutdownNow();
            log.warn("Interrupted while awaiting thread pool [{}] termination, forced shutdown, "
                    + "dropped {} pending task(s).", name, dropped.size());
            Thread.currentThread().interrupt();
        }
    }
}
