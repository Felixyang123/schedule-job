package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RoundRobinSelector implements InstanceSelector {
    private final AtomicInteger counter =new AtomicInteger();
    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances  )) {
            return null;
        }
        int index = counter.getAndIncrement() % instances.size();
        if (index < 0) {
            counter.set(0);
            index = 0;
        }
        return instances.get(index);
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.ROUND_ROBIN.getCode();
    }
}
