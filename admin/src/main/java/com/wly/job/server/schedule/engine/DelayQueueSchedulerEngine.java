package com.wly.job.server.schedule.engine;

import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;
import java.util.concurrent.DelayQueue;

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
