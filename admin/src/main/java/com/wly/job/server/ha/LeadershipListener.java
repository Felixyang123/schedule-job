package com.wly.job.server.ha;

/**
 * 调度权变更监听器（JobScheduler 实现，由 Spring 通过 ObjectProvider 收集）。
 * 监听器回调在选主线程内同步执行，实现类应保持轻量（如 JobScheduler 内部仅置状态/重建队列）。
 */
public interface LeadershipListener {

    /** 成为主节点：重建调度队列、启动引擎、补触发错过的单次任务 */
    void onBecomeLeader();

    /** 失去主节点转为 Standby：清空本地调度视图、停止引擎 */
    void onLoseLeadership();
}
