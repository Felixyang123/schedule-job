package com.wly.job.server.client.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public record PersistJobInstanceRegistry(InstanceRep instanceRep) implements Registry {
    @Override
    public List<JobInstance> discover(String jobname) {
        List<Instance> instances = instanceRep.list(Wrappers.<Instance>lambdaQuery().eq(Instance::getJobname, jobname)
                .eq(Instance::getStatus, Instance.ONLINE).ge(Instance::getExpireTime, new Date()));
        return instances.stream().map(JobBeanConverter::convert).toList();
    }

    @Override
    public boolean register(JobInstance jobInstance) {
        Instance instance = JobBeanConverter.convert(jobInstance).init();
        instance.setCreator("system");
        instance.setUpdater("system");
        return instanceRep.getBaseMapper().saveOrUpdate(instance) > 0;
    }

    @Override
    public void unregister(JobInstance jobInstance) {
        instanceRep.update(Wrappers.<Instance>lambdaUpdate().set(Instance::getStatus, Instance.OFFLINE)
                .eq(Instance::getJobname, jobInstance.getDiscoveryName())
                .eq(Instance::getHost, jobInstance.getHost())
                .eq(Instance::getPort, jobInstance.getPort())
                .eq(Instance::getStatus, Instance.ONLINE));
    }

    @Override
    public void batchRegister(List<JobInstance> jobInstances) {
        Date date = new Date();
        List<Instance> instances = jobInstances.stream().map(jobInstance -> {
            Instance instance = JobBeanConverter.convert(jobInstance).init();
            instance.setCreator("system");
            instance.setUpdater("system");
            return instance;
        }).toList();
        instanceRep.saveBatch(instances);
    }
}
