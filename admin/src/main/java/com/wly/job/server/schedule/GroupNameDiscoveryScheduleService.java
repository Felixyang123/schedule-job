package com.wly.job.server.schedule;

import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;

public class GroupNameDiscoveryScheduleService extends AbstractScheduleService {

    public GroupNameDiscoveryScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        super(client, loadBalancer, registry);
    }

    @Override
    protected ScheduleContext buildScheduleCtx(Job job) {
        return new ScheduleContext(job.getGroupName(), job.getStrategy());
    }
}
