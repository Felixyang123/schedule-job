package com.wly.job.server.ha;

import com.wly.job.server.dao.mapper.ScheduleLockMapper;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DB 租约锁实现：单行 CAS（owner = 我 或 租约已过期）即可抢锁/续约，见 ADR-0004。
 */
@RequiredArgsConstructor
public class DbLeaderElection implements LeaderElection {

    private final ScheduleLockMapper lockMapper;

    private final String owner;

    private final long leaseSeconds;

    private final AtomicBoolean rowEnsured = new AtomicBoolean(false);

    @Override
    public boolean acquireOrRenew() {
        ensureLockRow();
        // 单行 CAS：owner=我（续约）或租约已过期（抢锁）二选一，SQL 原子保证至多一个节点成功
        return lockMapper.acquireOrRenew(owner, leaseSeconds) == 1;
    }

    @Override
    public void release() {
        lockMapper.release(owner);
    }

    /**
     * 惰性确保锁行存在：仅在进程内第一次抢锁时执行一次（内存标志 CAS 防止并发重复建行）。
     */
    private void ensureLockRow() {
        if (rowEnsured.compareAndSet(false, true)) {
            lockMapper.ensureLockRow();
        }
    }
}
