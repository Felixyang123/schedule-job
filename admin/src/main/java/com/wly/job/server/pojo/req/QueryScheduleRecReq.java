package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 调度记录分页查询条件（管控后台 /admin/schedule-rec/page）。
 */
@Data
public class QueryScheduleRecReq {

    /** 作业 ID（精确过滤，可空） */
    private Long jobId;

    /** 调度请求 ID（精确过滤，可空） */
    private String requestId;

    /** 作业名（精确过滤，可空；经 job 表反查 jobId 集合后按集合过滤） */
    private String jobname;
}
