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

    /** 标记作业为在途（派发前调用，线程安全） */
    public void add(Long jobId) {
        if (jobId != null) {
            inFlight.add(jobId);
        }
    }

    /** 移除在途标记（失败/超时/成功后调用） */
    public void remove(Long jobId) {
        if (jobId != null) {
            inFlight.remove(jobId);
        }
    }

    /** 判断作业是否在途（对账/变更消费路径的竞态守卫） */
    public boolean contains(Long jobId) {
        return jobId != null && inFlight.contains(jobId);
    }

    /** 返回不可变快照，供对账扫描遍历以清理已消失任务的残留标记 */
    public Set<Long> snapshot() {
        return Set.copyOf(inFlight);
    }

    /**
     * 清空 in-flight（失去调度权时调用，交由新主按 At-Least-Once 重建）。
     */
    public void clear() {
        inFlight.clear();
    }
}
