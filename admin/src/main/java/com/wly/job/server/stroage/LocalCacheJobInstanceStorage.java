package com.wly.job.server.stroage;

import com.wly.job.common.bean.JobInstance;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class LocalCacheJobInstanceStorage implements Storage<JobInstance> {

    @Setter
    private ConcurrentMap<String, ConcurrentMap<String, JobInstance>> instancesCache = new ConcurrentHashMap<>();

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

        return instancesCache.entrySet().stream().flatMap(entry -> entry.getValue().values().stream()).toList();
    }
}
