package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;

import java.util.List;

/**
 * 实例选择器：定义一种路由策略下的单台执行器选择算法。
 * 各实现以 Spring Bean 注册，由 {@link InstanceSelectorFactory} 按 {@link #strategy()} 聚合分发。
 */
public interface InstanceSelector {

    /**
     * 从候选执行器列表中选一台。
     *
     * @return 选中的执行器；列表为空时返回 null
     */
    JobInstance select(List<JobInstance>  instances);

    /** 本选择器对应的路由策略码 */
    Integer strategy();
}
