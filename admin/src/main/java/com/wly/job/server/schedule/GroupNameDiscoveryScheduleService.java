package com.wly.job.server.schedule;

import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;

/**
 * 按分组发现调度服务实现：以作业分组名（groupName）为 discoveryKey 发现候选执行器，
 * 适用于多个作业共享同一组执行器的注册模式（service=GROUP）。
 */
public class GroupNameDiscoveryScheduleService extends AbstractScheduleService {

    public GroupNameDiscoveryScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry,
                                             ScheduleProps scheduleProps) {
        super(client, loadBalancer, registry, scheduleProps);
    }

    @Override
    protected ScheduleContext buildScheduleCtx(Job job) {
        return new ScheduleContext(job.getGroupName(), job.getStrategy());
    }
}
