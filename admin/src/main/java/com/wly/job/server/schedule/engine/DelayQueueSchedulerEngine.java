package com.wly.job.server.schedule.engine;

import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;
import java.util.concurrent.DelayQueue;

/**
 * 基于 JDK {@link DelayQueue} 的调度引擎实现（默认，schedule.engine=DELAY_QUEUE）。
 * 任务按 {@link ScheduleJob} 的到期纳秒排序，{@code take()} 阻塞直到队首到期；
 * 无内部时钟线程，{@code start}/{@code stop} 为空实现。线程安全由 DelayQueue 内部保证。
 */
public class DelayQueueSchedulerEngine implements SchedulerEngine {
    private final DelayQueue<ScheduleJob> queue = new DelayQueue<>();

    @Override
    public void add(ScheduleJob scheduleJob) {
        queue.add(scheduleJob);
    }

    @Override
    public void addAll(Collection<ScheduleJob> scheduleJobs) {
        queue.addAll(scheduleJobs);
    }

    @Override
    public ScheduleJob take() throws InterruptedException {
        return queue.take();
    }

    @Override
    public boolean remove(ScheduleJob scheduleJob) {
        return queue.remove(scheduleJob);
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public void clear() {
        queue.clear();
    }
}
