package com.wly.job.server.schedule;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractScheduleService implements ScheduleService {
    private final ScheduleJobClient client;

    private final LoadBalancer loadBalancer;

    private final Registry registry;

    @Override
    public void schedule(String requestId, Job job) {
        log.debug("Schedule job: {}", job);
        ScheduleContext scheduleContext = buildScheduleCtx(job);
        List<JobInstance> instances = registry.discover(scheduleContext.getDiscoveryKey());

        JobInstance instance = loadBalancer.choose(instances, scheduleContext.getStrategy());

        if (instance == null) {
            log.warn("No available schedule instance found, job: {}", job.getName());
            throw new ScheduleException("No available schedule instance found, job: " + job.getName());
        }

        ScheduleJobRequest scheduleJobRequest = ScheduleJobRequest.builder().requestId(requestId)
                .jobname(job.getName()).executeParam(job.getExecuteParam()).executionId(requestId).build();

        client.send(scheduleJobRequest, instance, job.getId(), isSingleRun(job));
    }

    protected abstract ScheduleContext buildScheduleCtx(Job job);

    private boolean isSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode();
    }
}
