package com.wly.job.server.ha;

/**
 * Admin 选主抽象（ADR-0004）。
 * 实现必须保证 acquireOrRenew() 的原子性：同一时刻至多一个节点返回 true。
 */
public interface LeaderElection {

    /**
     * 原子抢锁或续约；返回 true 表示本次调用后当前节点持有调度权。
     */
    boolean acquireOrRenew();

    /**
     * 主动释放调度权（优雅停机/失去主时调用）。
     */
    void release();
}
