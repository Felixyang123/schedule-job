package com.wly.job.server.schedule;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 调度上下文：封装一次派发所需的实例发现维度与路由策略，由调度服务实现类构建，
 * 供注册中心发现候选执行器与负载均衡选择实例使用。
 */
@Data
@AllArgsConstructor
public class ScheduleContext {
    /**
     * 实例服务发现的KEY
     */
    private String discoveryKey;

    /**
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;
}
