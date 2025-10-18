package com.wly.job.server.pojo.req;

import lombok.Data;

@Data
public class QueryScheduleRecReq {

    private Long jobId;

    private String requestId;

    private String jobname;
}
