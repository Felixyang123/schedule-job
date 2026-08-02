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

    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }
        String key = instances.getFirst().getDiscoveryKey();
        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.ROUND_ROBIN.getCode();
    }
}
