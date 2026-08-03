package com.wly.job.server.ha;

/**
 * HA 关闭时的恒主实现：单节点部署行为与现状完全一致。
 */
public class AlwaysLeaderElection implements LeaderElection {

    @Override
    public boolean acquireOrRenew() {
        return true;
    }

    @Override
    public void release() {
        // 无锁可释放
    }
}
