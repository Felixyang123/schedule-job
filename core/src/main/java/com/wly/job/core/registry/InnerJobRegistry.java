package com.wly.job.core.registry;

import com.wly.job.core.invocation.InnerJob;

/**
 * 本地任务注册表抽象：维护当前 Worker 节点已加载的 {@link InnerJob} 映射，
 * 供 JobInstanceHandler 在收到调度命令时按 jobname 查找可执行任务。
 * <p>
 * 实现参见 {@link com.wly.job.core.registry.DefaultInnerJobRegistry}。
 */
public interface InnerJobRegistry {

    boolean register(InnerJob job);

    InnerJob get(String jobname);
}
