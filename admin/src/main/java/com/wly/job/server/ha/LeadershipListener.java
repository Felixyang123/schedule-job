package com.wly.job.server.ha;

/**
 * 调度权变更监听器（JobScheduler 实现，由 Spring 通过 ObjectProvider 收集）。
 */
public interface LeadershipListener {

    void onBecomeLeader();

    void onLoseLeadership();
}
