package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;

import java.util.List;

/**
 * 负载均衡抽象：从候选执行器列表中按作业路由策略选择一台实例。
 * 实现类负责策略到 {@link InstanceSelector} 的映射与空列表的兜底处理。
 */
public interface LoadBalancer {

    /**
     * 从候选执行器中选一台。
     *
     * @param instances 候选执行器列表（可为空，由实现决定是否返回 null）
     * @param strategy  路由策略码（见 {@link com.wly.job.common.enumeration.ScheduleStrategyEnum}）
     * @return 选中的执行器；无候选或策略不支持时返回 null
     */
    JobInstance choose(List<JobInstance> instances, Integer strategy);
}
