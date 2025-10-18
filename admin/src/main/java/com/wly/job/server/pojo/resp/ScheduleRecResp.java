package com.wly.job.server.pojo.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleRecResp {
    private Long id;

    private Long jobId;

    private String jobName;

    private String requestId;

    private String executeParam;

    private String executeResult;

    /**
     * @see com.wly.job.server.enumeration.ScheduleJobStatusEnum
     */
    private Integer status;

    private String statusDesc;

    private Date scheduleTime;

    private Date completeTime;

    private String operator;

}
