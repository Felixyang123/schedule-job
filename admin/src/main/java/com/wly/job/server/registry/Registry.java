package com.wly.job.server.registry;

import com.wly.job.common.bean.JobInstance;

import java.util.List;

public interface Registry {
    List<JobInstance> discover(String name);

    boolean register(JobInstance jobInstance);

    void unregister(JobInstance jobInstance);

    void batchRegister(List<JobInstance> jobInstances);
}
