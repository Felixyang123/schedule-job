package com.wly.job.server.registry;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.stroage.Storage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public record DefaultInstanceRegistry(Storage<JobInstance> instanceStorage, ScheduleProps props) implements Registry {
    @Override
    public List<JobInstance> discover(String name) {
        return instanceStorage.list(List.of(name));
    }

    @Override
    public boolean register(JobInstance jobInstance) {
        if (!Boolean.TRUE.equals(props.getEnableRegisterInstance())) {
            return false;
        }
        jobInstance.setStatus(Instance.ONLINE);
        instanceStorage.put(jobInstance);
        return true;
    }

    @Override
    public void unregister(JobInstance jobInstance) {
        instanceStorage.remove(jobInstance);
    }

    @Override
    public void batchRegister(List<JobInstance> jobInstances) {

    }
}
