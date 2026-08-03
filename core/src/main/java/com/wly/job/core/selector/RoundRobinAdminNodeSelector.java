package com.wly.job.core.selector;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内原子游标轮询：每次心跳起点 +1，稳态下各 Admin 节点流量均衡。
 */
public class RoundRobinAdminNodeSelector implements AdminNodeSelector {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public int select(int size, String key) {
        return Math.floorMod(counter.getAndIncrement(), size);
    }
}
