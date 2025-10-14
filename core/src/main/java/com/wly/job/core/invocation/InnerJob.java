package com.wly.job.core.invocation;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;

public interface InnerJob {
    ScheduleJobResponse execute(ScheduleJobRequest request);

    String jobname();
}
