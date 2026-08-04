package com.wly.job.server.schedule;

import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;

/**
 * 默认调度服务实现：以作业名（name）为 discoveryKey 发现候选执行器，
 * 适用于执行器按作业名暴露实例的注册模式（service=DEFAULT）。
 */
public class DefaultScheduleServiceImpl extends AbstractScheduleService {

    public DefaultScheduleServiceImpl(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        super(client, loadBalancer, registry);
    }

    @Override
    protected ScheduleContext buildScheduleCtx(Job job) {
        return new ScheduleContext(job.getName(), job.getStrategy());
    }
}
