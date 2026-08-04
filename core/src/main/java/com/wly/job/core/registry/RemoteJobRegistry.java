package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;

/**
 * 远程注册抽象：Worker 通过 HTTP 将作业元数据与实例信息注册到 Admin 侧。
 * <p>
 * 对应 Admin 开放接口 {@code /open/job/register}（注册 Job 元数据）与
 * {@code /open/job/instance/register}（注册/续约实例心跳）；实现见
 * {@link com.wly.job.core.registry.DefaultRemoteJobRegistry}。
 */
public interface RemoteJobRegistry {
    void register(JobInfo jobInfo);

    void register(JobInstance jobInstance);
}
