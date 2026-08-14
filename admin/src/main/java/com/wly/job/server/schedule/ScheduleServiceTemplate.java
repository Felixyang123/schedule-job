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
import org.slf4j.MDC;

import java.util.List;
import java.util.function.Function;

/**
 * 调度服务实现：固定"发现候选执行器 -> 负载均衡选一台 -> 封包 RPC 发送"的执行链路，
 * 将"以什么为 discoveryKey 发现实例"这一差异点收敛为注入的 {@code discoveryKeyExtractor}
 * 函数（DEFAULT 模式取作业名，GROUP 模式取分组名）。
 */
@Slf4j
@RequiredArgsConstructor
public class ScheduleServiceTemplate implements ScheduleService {
    private final ScheduleJobClient client;

    private final LoadBalancer loadBalancer;

    private final Registry registry;

    /**
     * Admin 调度配置（{@code schedule.*}），派发请求时携带 {@code access-token} 供 Worker 侧 RPC 鉴权。
     */
    private final ScheduleProps scheduleProps;

    /**
     * 实例发现键提取函数：决定从注册中心发现候选执行器的维度（作业名 / 分组名）。
     */
    private final Function<Job, String> discoveryKeyExtractor;

    /**
     * {@inheritDoc}
     * <p>{@code requestId} 由上层 {@code ScheduleJobService} 在创建 RUNNING 调度记录时确定，
     * 显式传入确保 RPC 请求与 schedule_rec 使用同一 ID；MDC 仅用于日志上下文，不作为业务数据源。
     */
    @Override
    public void schedule(String requestId, Job job) {
        log.debug("Schedule job: {}", job);
        ScheduleContext scheduleContext = new ScheduleContext(discoveryKeyExtractor.apply(job), job.getStrategy());
        List<JobInstance> instances = registry.discover(scheduleContext.getDiscoveryKey());
        log.debug("job: {} -> candidates={}", job.getName(), instances.size());

        // 按作业配置的路由策略选择单台执行器；候选为空则抛出业务异常由上层记录失败重试
        JobInstance instance = loadBalancer.choose(instances, scheduleContext.getStrategy());

        if (instance == null) {
            log.warn("No available schedule instance found, job: {}", job.getName());
            throw new ScheduleException("No available schedule instance found, job: " + job.getName());
        }

        ScheduleJobRequest scheduleJobRequest = ScheduleJobRequest.builder()
                .traceId(MDC.get("traceId"))
                .requestId(requestId)
                .jobname(job.getName())
                .executeParam(job.getExecuteParam())
                // 当前安全模型按部署统一预共享 schedule.access-token（Spec 2026-08-05 §2.1/§2.9）；
                // Worker 先升级后配置同值。按实例发放凭证属于身份/密钥管理扩展，不在调度派发职责内。
                .token(scheduleProps.getAccessToken())
                .build();

        client.send(scheduleJobRequest, instance, job.getId(), isSingleRun(job));
    }

    private boolean isSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode();
    }
}
