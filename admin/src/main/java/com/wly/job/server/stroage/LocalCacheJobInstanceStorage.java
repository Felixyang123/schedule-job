package com.wly.job.server.stroage;

import com.wly.job.common.bean.JobInstance;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 进程内本地缓存执行器实例存储：基于双层 ConcurrentMap 实现（发现键 → 实例键 → 实例）。
 *
 * <p>作为默认的实例缓存实现，具备心跳过期惰性淘汰：
 * <ul>
 *   <li>{@link #list} 返回时同步剔除已过期实例（惰性清理）；</li>
 *   <li>{@link #start} 启动 30s 周期后台线程执行 {@link #clearExpired} 全量清理，避免失活实例滞留。</li>
 * </ul>
 * <p>注意：仅单 Admin 节点内存可见，多 Admin 集群下实例状态不共享；集群场景应选用 Redis / DB 存储。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LocalCacheJobInstanceStorage implements CacheStorage<JobInstance>, SmartLifecycle {

    /** 双层缓存：作业发现键 → 实例键 → 实例（@Setter 供测试注入） */
    @Setter
    private ConcurrentMap<String, ConcurrentMap<String, JobInstance>> instancesCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService clearExpiredExecutor;

    private volatile boolean running = false;

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    /** 幂等写入：以发现键 + 实例键双层定位，同一实例心跳续约时覆盖更新 */
    @Override
    public void put(JobInstance value) {
        instancesCache.computeIfAbsent(value.getDiscoveryKey(), k -> new ConcurrentHashMap<>()).put(value.getInstanceKey(), value);
    }

    /** 批量幂等写入 */
    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    /** 移除实例（仅操作发现键对应的实例集合） */
    @Override
    public void remove(JobInstance value) {
        instancesCache.computeIfPresent(value.getDiscoveryKey(), (k, instanceMap) -> {
            instanceMap.remove(value.getInstanceKey());
            return instanceMap;
        });
    }

    /** 清空全部缓存（主备切换清理场景使用） */
    @Override
    public void clear() {
        instancesCache.clear();
    }

    /** 仅新增：已存在的实例键不覆盖 */
    @Override
    public void add(JobInstance value) {
        instancesCache.computeIfAbsent(value.getDiscoveryKey(), k -> new ConcurrentHashMap<>()).putIfAbsent(value.getInstanceKey(), value);
    }

    /** 批量仅新增 */
    @Override
    public void addAll(Collection<JobInstance> values) {
        values.forEach(this::add);
    }

    /** 按发现键集合拉取实例，返回前惰性剔除已过期实例 */
    @Override
    public List<JobInstance> list(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }

        List<JobInstance> instances = new ArrayList<>();
        for (String key : keys) {
            ConcurrentMap<String, JobInstance> instanceMap = instancesCache.get(key);
            if (instanceMap != null) {
                for (Map.Entry<String, JobInstance> instanceEntry : instanceMap.entrySet()) {
                    JobInstance instance = instanceEntry.getValue();
                    if (instance.isExpired()) {
                        instanceMap.remove(instanceEntry.getKey());
                    } else {
                        instances.add(instance);
                    }
                }
            }
        }
        return instances;
    }

    /** 全量清理已过期实例（后台周期任务调用） */
    @Override
    public void clearExpired() {
        for (Map.Entry<String, ConcurrentMap<String, JobInstance>> serviceToInstancesEntry : instancesCache.entrySet()) {
            ConcurrentMap<String, JobInstance> instanceMap = serviceToInstancesEntry.getValue();
            for (Map.Entry<String, JobInstance> instanceEntry : instanceMap.entrySet()) {
                JobInstance instance = instanceEntry.getValue();
                if (instance.isExpired()) {
                    instanceMap.remove(instanceEntry.getKey());
                }
            }
        }
    }

    /** 启动后台过期清理线程（30s 固定延迟） */
    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;
        clearExpiredExecutor = Executors.newSingleThreadScheduledExecutor();
        clearExpiredExecutor.scheduleWithFixedDelay(this::clearExpired, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        log.info("LocalCacheJobInstanceStorage started.");
    }

    /** 优雅停止：置 running=false 并关闭后台线程池 */
    @Override
    public void stop() {
        this.running = false;
        if (clearExpiredExecutor != null) {
            clearExpiredExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
