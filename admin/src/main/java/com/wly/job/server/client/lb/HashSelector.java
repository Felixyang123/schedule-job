package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 哈希选择器（策略码 HASH）：按 discoveryKey（作业名/分组名）的哈希取模选台。
 * 同一 discoveryKey 恒落在同一执行器，保证同类任务对目标执行器亲和（如本地缓存命中）。
 */
@Component
public class HashSelector implements InstanceSelector {

    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }
        String key = instances.getFirst().getDiscoveryKey();
        // floorMod 保证负哈希也落在 [0, size) 区间
        int hash = key == null ? 0 : key.hashCode();
        return instances.get(Math.floorMod(hash, instances.size()));
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.HASH.getCode();
    }
}
