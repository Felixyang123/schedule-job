package com.wly.job.core.invocation;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;

/**
 * Worker 侧本地任务抽象：代表一个可被 Admin 调度命令触发的可执行作业。
 * <p>
 * 当前唯一实现 {@link MethodInvocationJob} 包装被 {@code @ScheduleJob} 标注的方法；
 * 本地任务注册表 {@link com.wly.job.core.registry.InnerJobRegistry} 以 {@code jobname}
 * 为键维护本接口实例。
 */
public interface InnerJob {
    ScheduleJobResponse execute(ScheduleJobRequest request);

    String jobname();
}
