package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;

import java.util.List;

public interface InstanceSelector {
    JobInstance select(List<JobInstance>  instances);

    Integer strategy();
}
