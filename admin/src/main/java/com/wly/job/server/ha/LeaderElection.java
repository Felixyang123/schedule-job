package com.wly.job.server.ha;

/**
 * Admin 选主抽象（ADR-0004）。
 * 实现必须保证 acquireOrRenew() 的原子性：同一时刻至多一个节点返回 true。
 */
public interface LeaderElection {

    /**
     * 原子抢锁或续约；返回 true 表示本次调用后当前节点持有调度权。
     * 抢锁与续约共用同一原子操作：未持有锁时尝试抢锁（拿不到即失败），
     * 已持有锁时幂等续约（仅当锁仍属于自己时延长租约，防止误续他人锁）。
     */
    boolean acquireOrRenew();

    /**
     * 主动释放调度权（优雅停机/失去主时调用）；实现必须保证只释放自己的锁。
     */
    void release();
}
