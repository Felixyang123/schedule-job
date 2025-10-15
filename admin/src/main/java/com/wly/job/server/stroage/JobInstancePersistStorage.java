package com.wly.job.server.stroage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Component
public record JobInstancePersistStorage(InstanceRep instanceRep) implements Storage<JobInstance> {

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void put(JobInstance value) {
        Instance instance = JobBeanConverter.convert(value).init();
        instance.setCreator("system");
        instance.setUpdater("system");
        instanceRep.getBaseMapper().saveOrUpdate(instance);
    }

    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    @Override
    public void remove(JobInstance value) {
        instanceRep.update(Wrappers.<Instance>lambdaUpdate().set(Instance::getStatus, Instance.OFFLINE)
                .eq(Instance::getName, value.getDiscoveryKey())
                .eq(Instance::getHost, value.getHost())
                .eq(Instance::getPort, value.getPort())
                .eq(Instance::getStatus, Instance.ONLINE));
    }

    @Override
    public void clear() {
    }

    @Override
    public void add(JobInstance value) {
        Instance instance = JobBeanConverter.convert(value).init();
        instance.setCreator("system");
        instance.setUpdater("system");
        instanceRep.save(instance);
    }

    @Override
    public void addAll(Collection<JobInstance> values) {
        List<Instance> instances = values.stream().map(jobInstance -> {
            Instance instance = JobBeanConverter.convert(jobInstance).init();
            instance.setCreator("system");
            instance.setUpdater("system");
            return instance;
        }).toList();
        instanceRep.saveBatch(instances);
    }

    @Override
    public List<JobInstance> list(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }

        List<Instance> instances = instanceRep.list(Wrappers.<Instance>lambdaQuery().in(Instance::getName, keys)
                .eq(Instance::getStatus, Instance.ONLINE).ge(Instance::getExpireTime, new Date()));
        return instances.stream().map(JobBeanConverter::convert).toList();
    }
}
