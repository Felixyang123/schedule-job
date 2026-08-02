package com.wly.job.server.stroage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class RefreshJobInstanceStorage implements RefreshStorage<JobInstance>, SmartLifecycle {

    private final JobInstancePersistStorage persistStorage;

    /**
     * 提供抽象接口，默认提供local map的实现
     * 可以实现guava、redis等其他缓存
     */
    private final Storage<JobInstance> cacheStorage;

    private volatile boolean running = true;

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void put(JobInstance value) {
        persistStorage.put(value);
        cacheStorage.put(value);
    }

    @Override
    public void putAll(Collection<JobInstance> values) {
        persistStorage.putAll(values);
        cacheStorage.putAll(values);
    }

    @Override
    public void remove(JobInstance value) {
        persistStorage.remove(value);
        cacheStorage.remove(value);
    }

    @Override
    public void clear() {
        persistStorage.clear();
        cacheStorage.clear();
    }

    @Override
    public void add(JobInstance value) {
        persistStorage.add(value);
        cacheStorage.add(value);
    }

    @Override
    public void addAll(Collection<JobInstance> values) {
        persistStorage.addAll(values);
        cacheStorage.addAll(values);
    }

    @Override
    public List<JobInstance> list(Collection<String> keys) {
        List<JobInstance> instances = cacheStorage.list(keys);
        if (CollectionUtils.isEmpty(instances)) {
            instances = persistStorage.list(keys);
        }
        return instances;
    }


    @Override
    public boolean running() {
        return this.running;
    }

    @Override
    public List<JobInstance> newDataCollection(RefreshContext<JobInstance> refreshContext) {
        long cursor = Optional.of(refreshContext.getCursor()).orElse(0L);
        InstanceRep instanceRep = persistStorage.instanceRep();
        List<Instance> onlineInstances = instanceRep.list(Wrappers.<Instance>lambdaQuery().eq(Instance::getStatus, Instance.ONLINE)
                .ge(Instance::getExpireTime, new Date()).gt(Instance::getId, cursor));
        if (!CollectionUtils.isEmpty(onlineInstances)) {
            refreshContext.setNewDataCollection(onlineInstances.stream().map(JobBeanConverter::convert).collect(Collectors.toList()));
            refreshContext.setCursor(onlineInstances.getLast().getId());
        }
        return refreshContext.getNewDataCollection();
    }

    @Override
    public void refresh(RefreshContext<JobInstance> refreshContext) {
        refreshContext.getNewDataCollection().forEach(cacheStorage::put);
    }

    @Override
    public void start() {
        RefreshStorage.super.start();
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
