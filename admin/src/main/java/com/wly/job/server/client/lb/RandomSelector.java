package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机选择器（策略码 RANDOM）：从候选执行器中等概率随机选一台。
 * 使用 ThreadLocalRandom 避免并发选择时的锁竞争。
 */
@Component
public class RandomSelector implements InstanceSelector {

    @Override
    public JobInstance select(List<JobInstance> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }

    @Override
    public Integer strategy() {
        return ScheduleStrategyEnum.RANDOM.getCode();
    }
}
