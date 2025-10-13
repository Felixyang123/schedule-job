package com.wly.job.server.client.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LocalCacheJobInstanceRegistry implements Registry, SmartLifecycle {
    private final PersistJobInstanceRegistry persistRegistry;
    private ConcurrentMap<String, ConcurrentMap<String, JobInstance>> jobInstancesCache = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    @Override
    public List<JobInstance> discover(String jobname) {
        ConcurrentMap<String, JobInstance> jobInstancesMap = jobInstancesCache.get(jobname);
        if (CollectionUtils.isEmpty(jobInstancesMap)) {
            return List.of();
        }
        Date date = new Date();
        return jobInstancesMap.values().stream().filter(jobInstance -> Objects.equals(jobInstance.getStatus(), Instance.ONLINE)
                && !date.after(jobInstance.getExpireTime())).toList();
    }

    @Override
    public boolean register(JobInstance jobInstance) {
        jobInstance.setStatus(Instance.ONLINE);
        if (persistRegistry.register(jobInstance)) {
            String instanceKey = buildInstanceKey(jobInstance);
            jobInstancesCache.computeIfAbsent(jobInstance.getDiscoveryName(), k -> new ConcurrentHashMap<>()).put(instanceKey, jobInstance);
            return true;
        }
        return false;
    }

    @Override
    public void unregister(JobInstance jobInstance) {
        persistRegistry.unregister(jobInstance);
        ConcurrentMap<String, JobInstance> instances = jobInstancesCache.get(jobInstance.getDiscoveryName());
        if (!CollectionUtils.isEmpty(instances)) {
            String instanceKey = buildInstanceKey(jobInstance);
            instances.remove(instanceKey);
        }
    }

    @Override
    public void batchRegister(List<JobInstance> jobInstances) {
        for (JobInstance jobInstance : jobInstances) {
            register(jobInstance);
        }
    }

    @Override
    public void start() {
        Thread refreshInstancesCacheThread = new Thread(() -> {
            while (running) {
                InstanceRep instanceRep = persistRegistry.instanceRep();
                List<Instance> onlineInstances = instanceRep.list(Wrappers.<Instance>lambdaQuery().eq(Instance::getStatus, Instance.ONLINE).ge(Instance::getExpireTime, new Date()));
                Map<String, List<Instance>> instancesMap = onlineInstances.stream().collect(Collectors.groupingBy(Instance::getJobname));
                ConcurrentMap<String, ConcurrentMap<String, JobInstance>> jobInstancesCacheNew = new ConcurrentHashMap<>();
                for (Map.Entry<String, List<Instance>> entry : instancesMap.entrySet()) {
                    List<Instance> instances = entry.getValue();
                    ConcurrentMap<String, JobInstance> jobInstanceMap = instances.stream().map(JobBeanConverter::convert)
                            .collect(Collectors.toConcurrentMap(LocalCacheJobInstanceRegistry::buildInstanceKey, Function.identity()));
                    jobInstancesCacheNew.put(entry.getKey(), jobInstanceMap);
                }
                this.jobInstancesCache = jobInstancesCacheNew;
                try {
                    Thread.sleep(30 * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        refreshInstancesCacheThread.setName("refresh-instances-cache-thread");
        refreshInstancesCacheThread.start();
    }

    private static String buildInstanceKey(JobInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
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
