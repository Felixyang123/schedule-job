package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Component
public class HashSelector implements InstanceSelector {

    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }
        String key = instances.getFirst().getDiscoveryKey();
        int hash = key == null ? 0 : key.hashCode();
        return instances.get(Math.floorMod(hash, instances.size()));
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.HASH.getCode();
    }
}
