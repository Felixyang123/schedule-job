package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.exception.ScheduleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultLoadBalancer implements LoadBalancer {
    private final InstanceSelectorFactory selectorFactory;

    @Override
    public JobInstance choose(List<JobInstance> instances, Integer strategy) {
        if (CollectionUtils.isEmpty(instances)) {
            return null;
        }

        InstanceSelector selector = selectorFactory.select(strategy);

        if (selector == null) {
            log.error("No instance selector found, strategy: {}", strategy);
            throw new ScheduleException("No instance selector found");
        }

        return selector.select(instances);
    }
}
