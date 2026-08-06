package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 作业级隔离轮询选择器。
 * <p>
 * 每个作业（discoveryKey）使用独立的计数器，避免不同作业相互干扰轮询节奏；
 * 见 docs/adr/0002-job-level-and-cluster-coordinated-round-robin.md。
 */
@Service
public class RoundRobinSelector implements InstanceSelector {

    /** 计数器键数上限：动态/海量发现键场景下防无界增长（触顶整体重置，轮询位置归零，无正确性影响） */
    private static final int MAX_COUNTER_KEYS = 100_000;

    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }
        String key = instances.getFirst().getDiscoveryKey();
        if (counters.size() > MAX_COUNTER_KEYS) {
            counters.clear();
        }
        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.ROUND_ROBIN.getCode();
    }
}
