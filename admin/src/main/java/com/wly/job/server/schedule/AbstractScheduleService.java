package com.wly.job.server.schedule;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 调度服务模板实现：固定"发现候选执行器 -> 负载均衡选一台 -> 封包 RPC 发送"的执行链路，
 * 将"以什么为 discoveryKey 发现实例"这一差异点抽象为 {@link #buildScheduleCtx} 由子类决定
 * （按作业名发现或按分组名发现）。
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractScheduleService implements ScheduleService {
    private final ScheduleJobClient client;

    private final LoadBalancer loadBalancer;

    private final Registry registry;

    /**
     * Admin 调度配置（{@code schedule.*}），派发请求时携带 {@code access-token} 供 Worker 侧 RPC 鉴权。
     */
    private final ScheduleProps scheduleProps;

    @Override
    public void schedule(String requestId, Job job) {
        log.debug("Schedule job: {}", job);
        ScheduleContext scheduleContext = buildScheduleCtx(job);
        List<JobInstance> instances = registry.discover(scheduleContext.getDiscoveryKey());

        // 按作业配置的路由策略选择单台执行器；候选为空则抛出业务异常由上层记录失败重试
        JobInstance instance = loadBalancer.choose(instances, scheduleContext.getStrategy());

        if (instance == null) {
            log.warn("No available schedule instance found, job: {}", job.getName());
            throw new ScheduleException("No available schedule instance found, job: " + job.getName());
        }

        ScheduleJobRequest scheduleJobRequest = ScheduleJobRequest.builder().requestId(requestId)
                .jobname(job.getName()).executeParam(job.getExecuteParam()).executionId(requestId)
                .token(scheduleProps.getAccessToken()).build();

        client.send(scheduleJobRequest, instance, job.getId(), isSingleRun(job));
    }

    /**
     * 构建调度上下文：由子类决定实例发现维度与路由策略来源。
     */
    protected abstract ScheduleContext buildScheduleCtx(Job job);

    private boolean isSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode();
    }
}
