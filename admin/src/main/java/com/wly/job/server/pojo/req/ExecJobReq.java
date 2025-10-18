package com.wly.job.server.pojo.req;

import lombok.Data;

@Data
public class ExecJobReq {

    private Long jobId;

    private String executeParam;
}
