package com.wly.job.server.registry;

import com.wly.job.common.bean.JobInstance;

import java.util.List;

/**
 * 执行器实例注册中心抽象。
 *
 * <p>定义执行器实例的注册 / 注销 / 发现能力，用于调度派发前发现候选执行器节点。
 * 不同实现对应不同后端存储：
 * <ul>
 *   <li>{@link DefaultInstanceRegistry}：进程内本地存储（LocalCache / Redis / DB）。</li>
 *   <li>{@link RemoteRegisterCenterRegistry}：接入外部注册中心（RegistryClient）。</li>
 * </ul>
 */
public interface Registry {

    /**
     * 按作业名发现可用执行器实例列表（调度派发时作为候选节点）。
     *
     * @param name 作业发现键（作业名 / 应用名）
     * @return 可用执行器实例列表
     */
    List<JobInstance> discover(String name);

    /**
     * 注册 / 刷新执行器实例。实现通常为幂等：存在则更新心跳，不存在则新增。
     *
     * @param jobInstance 执行器实例信息
     * @return 是否注册成功（实例注册开关关闭时返回 false）
     */
    boolean register(JobInstance jobInstance);
}
