package com.wly.job.core.registry;

import com.wly.job.core.invocation.InnerJob;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
