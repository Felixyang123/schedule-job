package com.wly.job.server.schedule.engine;

import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;

public interface SchedulerEngine {
    void add(ScheduleJob scheduleJob);
    
    void addAll(Collection<ScheduleJob> scheduleJobs);
    
    ScheduleJob take() throws InterruptedException;

    /**
     * 从调度引擎中移除指定任务（任务被禁用/删除时调用）。
     *
     * @return 是否成功移除
     */
    boolean remove(ScheduleJob scheduleJob);

    boolean isEmpty();
    
    default void start() {}
    
    default void stop() {}
}
