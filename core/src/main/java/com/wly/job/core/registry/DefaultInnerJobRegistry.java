package com.wly.job.core.registry;

import com.wly.job.core.invocation.InnerJob;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认本地任务注册表：基于 ConcurrentHashMap 实现 {@link InnerJobRegistry}。
 * <p>
 * 以 jobname 为键，register 使用 putIfAbsent 保证同名任务不被重复覆盖（返回 false 表示已存在）。
 */
@Slf4j
public class DefaultInnerJobRegistry implements InnerJobRegistry {
    private final ConcurrentMap<String, InnerJob> jobMap = new ConcurrentHashMap<>();

    @Override
    public boolean register(InnerJob job) {
        return jobMap.putIfAbsent(job.jobname(), job) == null;
    }

    @Override
    public InnerJob get(String jobname) {
        return jobMap.get(jobname);
    }
}
