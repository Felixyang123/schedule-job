package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;

public interface RemoteJobRegistry {
    void register(JobInfo jobInfo);

    void register(JobInstance jobInstance);
}
