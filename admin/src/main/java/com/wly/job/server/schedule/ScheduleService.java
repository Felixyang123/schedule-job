package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;

/**
 * 作业调度服务抽象：负责把到期作业派发到执行器（Worker）。
 * 实现类决定实例发现的维度（按作业名或按分组名），派发前先经注册中心发现候选执行器，
 * 再经负载均衡选择单台实例发送（见 {@link ScheduleServiceTemplate}）。
 */
public interface ScheduleService {
    /**
     * 派发一次作业执行。
     *
     * @param requestId 调度执行 ID（R2，与 schedule_rec 关联）
     * @param job       待派发的作业
     */
    void schedule(String requestId, Job job);
}
