package com.wly.job.server.schedule;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.client.registry.LocalCacheJobInstanceRegistry;
import com.wly.job.server.dao.entity.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleService {
    private final ScheduleJobClient client;
    private final LoadBalancer loadBalancer;
    private final LocalCacheJobInstanceRegistry registry;

    public void schedule(String requestId, Job job) {
        log.debug("Schedule job: {}", job);
        List<JobInstance> instances = registry.discover(job.getName());

        JobInstance instance = loadBalancer.choose(instances, job.getStrategy());

        if (instance == null) {
            throw new ScheduleException("No available schedule instance found, job: {}", job.getName());
        }

        ScheduleJobRequest scheduleJobRequest = ScheduleJobRequest.builder().requestId(requestId)
                .jobname(job.getName()).executeParam(job.getExecuteParam()).executionId(requestId).build();

        client.send(scheduleJobRequest, instance);
    }

}
