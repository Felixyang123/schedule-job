package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobRequest {
    private String requestId;

    /**
     * job执行记录ID
     */
    private String executionId;

    private String jobname;

    private String executeParam;
}
