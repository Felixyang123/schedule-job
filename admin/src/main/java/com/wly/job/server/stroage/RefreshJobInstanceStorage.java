package com.wly.job.server.stroage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RefreshJobInstanceStorage implements Storage<JobInstance>, SmartLifecycle {
    private final JobInstancePersistStorage persistStorage;
    private final LocalCacheJobInstanceStorage cacheJobInstanceStorage;
    private volatile boolean running = true;

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void put(JobInstance value) {
        persistStorage.put(value);
        cacheJobInstanceStorage.put(value);
    }

    @Override
    public void putAll(Collection<JobInstance> values) {
        persistStorage.putAll(values);
        cacheJobInstanceStorage.putAll(values);
    }

    @Override
    public void remove(JobInstance value) {
        persistStorage.remove(value);
        cacheJobInstanceStorage.remove(value);
    }

    @Override
    public void clear() {
        persistStorage.clear();
        cacheJobInstanceStorage.clear();
    }

    @Override
    public void add(JobInstance value) {
        persistStorage.add(value);
        cacheJobInstanceStorage.add(value);
    }

    @Override
    public void addAll(Collection<JobInstance> values) {
        persistStorage.addAll(values);
        cacheJobInstanceStorage.addAll(values);
    }

    @Override
    public List<JobInstance> list(Collection<String> keys) {
        return cacheJobInstanceStorage.list(keys);
    }

    @Override
    public void start() {
        Thread refreshInstancesCacheThread = new Thread(() -> {
            while (running) {
                InstanceRep instanceRep = persistStorage.instanceRep();
                List<Instance> onlineInstances = instanceRep.list(Wrappers.<Instance>lambdaQuery().eq(Instance::getStatus, Instance.ONLINE).ge(Instance::getExpireTime, new Date()));
                Map<String, List<Instance>> instancesMap = onlineInstances.stream().collect(Collectors.groupingBy(Instance::getName));
                ConcurrentMap<String, ConcurrentMap<String, JobInstance>> jobInstancesCacheNew = new ConcurrentHashMap<>();
                for (Map.Entry<String, List<Instance>> entry : instancesMap.entrySet()) {
                    List<Instance> instances = entry.getValue();
                    ConcurrentMap<String, JobInstance> jobInstanceMap = instances.stream().map(JobBeanConverter::convert)
                            .collect(Collectors.toConcurrentMap(JobInstance::getInstanceKey, Function.identity()));
                    jobInstancesCacheNew.put(entry.getKey(), jobInstanceMap);
                }
                cacheJobInstanceStorage.setInstancesCache(jobInstancesCacheNew);
                try {
                    Thread.sleep(30 * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        refreshInstancesCacheThread.setName("refresh-instances-thread");
        refreshInstancesCacheThread.start();
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
