package com.wly.job.server.stroage;

import com.wly.job.common.bean.JobInstance;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
public class LocalCacheJobInstanceStorage implements CacheStorage<JobInstance> {

    @Setter
    private ConcurrentMap<String, ConcurrentMap<String, JobInstance>> instancesCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService clearExpiredExecutor;

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void put(JobInstance value) {
        instancesCache.computeIfAbsent(value.getDiscoveryKey(), k -> new ConcurrentHashMap<>()).put(value.getInstanceKey(), value);
    }

    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    @Override
    public void remove(JobInstance value) {
        instancesCache.computeIfPresent(value.getDiscoveryKey(), (k, instanceMap) -> {
            instanceMap.remove(value.getInstanceKey());
            return instanceMap;
        });
    }

    @Override
    public void clear() {
        instancesCache.clear();
    }

    @Override
    public void add(JobInstance value) {
        instancesCache.computeIfAbsent(value.getDiscoveryKey(), k -> new ConcurrentHashMap<>()).putIfAbsent(value.getInstanceKey(), value);
    }

    @Override
    public void addAll(Collection<JobInstance> values) {
        values.forEach(this::add);
    }

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

    @Override
    public void start() {
        clearExpiredExecutor = Executors.newSingleThreadScheduledExecutor();
        clearExpiredExecutor.scheduleWithFixedDelay(this::clearExpired, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        clearExpiredExecutor.shutdownNow();
    }
}
