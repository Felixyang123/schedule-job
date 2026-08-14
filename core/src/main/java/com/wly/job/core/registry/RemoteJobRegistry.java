package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;

/**
 * 远程注册抽象：Worker 通过 HTTP 将作业元数据与实例信息注册到 Admin 侧。
 * <p>
 * 对应 Admin 开放接口 {@code /open/job/register}（注册 Job 元数据）与
 * {@code /open/job/instance/register}（注册/续约实例心跳）；实现见
 * {@link com.wly.job.core.registry.DefaultRemoteJobRegistry}。
 * <p>
 * 两类注册在调用语义上完全独立：{@code JobInfo} 只在 Worker 启动时注册一次，
 * {@code JobInstance} 由心跳任务周期续约（见 Spec 2026-08-12 作业注册与实例注册解耦）。
 */
public interface RemoteJobRegistry {

    /**
     * 注册作业元数据（Worker 启动时一次性调用）。
     *
     * @param jobInfo     作业元数据，不含实例信息
     * @param instanceKey 本 Worker 的实例键（{@code discoveryKey:host:port}），
     *                    <b>仅用于选择 Admin 节点</b>：与实例注册共用同一 key，保证 HASH 策略下
     *                    同一 Worker 的两类注册命中同一节点；该值不进入请求体
     */
    void register(JobInfo jobInfo, String instanceKey);

    /** 注册 / 续约实例心跳（周期性调用） */
    void register(JobInstance jobInstance);
}
