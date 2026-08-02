package com.wly.job.server.schedule.engine;

import com.wly.job.common.timewheel.TimeWheel;
import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public class TimeWheelSchedulerEngine implements SchedulerEngine {
    private final LinkedBlockingQueue<ScheduleJob> readyQueue = new LinkedBlockingQueue<>();
    private final InnerTimeWheel timeWheel;
    private Thread clockThread;

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

    @Override
    public boolean remove(ScheduleJob scheduleJob) {
        return timeWheel.remove(scheduleJob, scheduleJob.expireNanos() / 1_000_000L);
    }

    @Override
    public boolean isEmpty() {
        return readyQueue.isEmpty();
    }

    @Override
    public void start() {
        clockThread = new Thread(() -> {
            timeWheel.clock(items -> {
                if (items != null && !items.isEmpty()) {
                    readyQueue.addAll(items);
                }
                return null; // 不自动重新加入时间轮
            });
        });
        clockThread.setName("time-wheel-clock-thread");
        clockThread.setDaemon(true);
        clockThread.start();
    }

    @Override
    public void stop() {
        timeWheel.stop();
        if (clockThread != null) {
            clockThread.interrupt();
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
