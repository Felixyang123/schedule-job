package com.wly.job.common.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程池生命周期工具：统一"优雅停机"模板（AGENTS.md §5.2 约束）。
 * <p>
 * 模式：先 {@code shutdown()} 停止接收新任务，有界等待在途任务完成；超时未完成则
 * {@code shutdownNow()} 强制中断；等待期间被中断时同样强制中断并复位中断标志。
 * 各 SmartLifecycle 组件停机时须先置 {@code running=false} 让循环线程自行退出，
 * 再调用本方法关闭线程池，防止 {@code awaitTermination} 无限等待或线程残留。
 */
public final class ThreadPoolUtils {

    private ThreadPoolUtils() {
    }

    /**
     * 优雅关闭线程池（空引用安全）。
     *
     * @param executor 待关闭线程池，可为 null
     * @param timeout  有界等待时长
     * @param unit     等待时长单位
     */
    public static void shutdownGracefully(ExecutorService executor, long timeout, TimeUnit unit) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
