package com.wly.job.server.schedule.engine;

import com.wly.job.server.schedule.ScheduleJob;

import java.util.Collection;

/**
 * 调度引擎抽象：管理"到期即触发"的队列语义，是派发线程唯一的数据来源。
 * 实现需保证并发安全：{@code add}/{@code remove} 由对账线程与 worker 线程并发调用，
 * {@code take()} 由派发线程阻塞等待。
 * <p>
 * 默认实现为 JDK {@link DelayQueue}（DelayQueueSchedulerEngine），
 * 大规模任务场景可切换时间轮实现（TimeWheelSchedulerEngine，{@code schedule.engine} 配置）。
 * 主备切换时调用 {@link #clear()}（O(n) 清空）并配合 {@link #start()}/{@link #stop()} 控制时钟。
 */
public interface SchedulerEngine {

    /** 入队一个到期任务（幂等由上层 queuedJobs 保证，本接口允许重复入队） */
    void add(ScheduleJob scheduleJob);

    /** 批量入队 */
    void addAll(Collection<ScheduleJob> scheduleJobs);

    /** 阻塞取出一个到期任务（未到期时挂起） */
    ScheduleJob take() throws InterruptedException;

    /**
     * 从调度引擎中移除指定任务（任务被禁用/删除时调用）。
     *
     * @return 是否成功移除
     */
    boolean remove(ScheduleJob scheduleJob);

    /** 当前是否有任务在队列中（派发线程停机判断用） */
    boolean isEmpty();

    /** 清空队列（主备切换时调用，丢弃本地视图） */
    void clear();

    /** 启动内部时钟（如时间轮 tick 线程；无内部时钟的实现可空实现） */
    default void start() {}

    /** 停止内部时钟 */
    default void stop() {}
}
