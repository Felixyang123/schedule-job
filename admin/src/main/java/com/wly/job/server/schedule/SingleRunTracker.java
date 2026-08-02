package com.wly.job.server.schedule;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次任务 in-flight 跟踪器（内存态）。
 * 契约：At-Least-Once——Admin 重启后集合丢失，已派发未回执的单次任务可能被重新入队，Worker 必须幂等。
 * 失败回调会显式移除；成功置 Finished 后由对账扫描清扫。
 */
@Component
public class SingleRunTracker {

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public void add(Long jobId) {
        if (jobId != null) {
            inFlight.add(jobId);
        }
    }

    public void remove(Long jobId) {
        if (jobId != null) {
            inFlight.remove(jobId);
        }
    }

    public boolean contains(Long jobId) {
        return jobId != null && inFlight.contains(jobId);
    }

    public Set<Long> snapshot() {
        return Set.copyOf(inFlight);
    }
}
