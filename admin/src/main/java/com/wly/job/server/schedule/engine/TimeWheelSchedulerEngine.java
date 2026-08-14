package com.wly.job.server.schedule.engine;

import com.wly.job.common.timewheel.TimeWheel;
import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 基于时间轮的调度引擎实现（schedule.engine=TIME_WHEEL，适用于大规模任务场景）。
 * <p>
 * 结构：内部时间轮按 tick 到期后把任务投递到 {@code readyQueue}（LinkedBlockingQueue），
 * 派发线程从 {@code readyQueue} 取任务；{@code start()} 启动时钟线程驱动时间轮滚动。
 * 已到期任务直接进入就绪队列，避免负槽位问题；{@code remove}/{@code clear} 需同时处理时间轮与就绪队列。
 */
public class TimeWheelSchedulerEngine implements SchedulerEngine {

    /** 重启前等待旧时钟线程退出的上限（毫秒） */
    private static final long CLOCK_THREAD_JOIN_WAIT_MS = 2000L;

    private final LinkedBlockingQueue<ScheduleJob> readyQueue = new LinkedBlockingQueue<>();
    private final InnerTimeWheel timeWheel;
    private Thread clockThread;
    private volatile boolean running;

    public TimeWheelSchedulerEngine() {
        // 默认 1 秒 tick，60 个槽位（1 分钟轮转）
        this.timeWheel = new InnerTimeWheel(1, 60);
    }

    @Override
    public void add(ScheduleJob scheduleJob) {
        // 转换为毫秒数
        long expireMs = scheduleJob.expireNanos() / 1_000_000L;
        if (expireMs <= System.currentTimeMillis()) {
            // 已到期任务直接进入就绪队列，避免时间轮负槽位问题
            readyQueue.offer(scheduleJob);
        } else {
            timeWheel.add(scheduleJob, expireMs);
        }
    }

    @Override
    public void addAll(Collection<ScheduleJob> scheduleJobs) {
        scheduleJobs.forEach(this::add);
    }

    @Override
    public ScheduleJob take() throws InterruptedException {
        return readyQueue.take();
    }

    /**
     * 移除任务：先尝试从时间轮摘除未到期条目（按反向索引精确定位），
     * 未命中时再从就绪队列摘除已到期条目。
     */
    @Override
    public boolean remove(ScheduleJob scheduleJob) {
        return timeWheel.remove(scheduleJob) || readyQueue.remove(scheduleJob);
    }

    @Override
    public boolean isEmpty() {
        // 仅反映就绪队列；时间轮内未到期的任务不计入，供派发线程停机判断用
        return readyQueue.isEmpty();
    }

    @Override
    public void clear() {
        readyQueue.clear();
        timeWheel.clear();
    }

    /**
     * 启动时钟线程；支持 {@link #stop()} 后重新启动（主备切换场景）。
     * 重启时先等待上一轮时钟线程退出，避免两个时钟线程并发消费同一时间轮。
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        awaitClockThreadTermination();
        running = true;
        timeWheel.start();
        clockThread = new Thread(() -> timeWheel.clock(items -> {
            if (items != null && !items.isEmpty()) {
                readyQueue.addAll(items);
            }
            return null; // 不自动重新加入时间轮，到期任务交给派发线程
        }));
        clockThread.setName("time-wheel-clock-thread");
        clockThread.setDaemon(true);
        clockThread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        timeWheel.stop();
        if (clockThread != null) {
            clockThread.interrupt();
        }
    }

    /** 等待上一轮时钟线程退出（有界等待，超时后仍继续启动，旧线程已被置 stop 会自行结束） */
    private void awaitClockThreadTermination() {
        if (clockThread == null) {
            return;
        }
        try {
            clockThread.join(CLOCK_THREAD_JOIN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class InnerTimeWheel extends TimeWheel<ScheduleJob> {
        public InnerTimeWheel(int tick, int wheelSize) {
            super(tick, wheelSize);
        }

        @Override
        protected void add(ScheduleJob item) {
            long expireMs = item.expireNanos() / 1_000_000L;
            add(item, expireMs);
        }
    }
}
