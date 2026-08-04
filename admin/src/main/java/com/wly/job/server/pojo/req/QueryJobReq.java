package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 作业分页查询条件（管控后台 /admin/job/page）。
 */
@Data
public class QueryJobReq {

    /** 作业分组名（前缀匹配，可空） */
    private String groupName;

    /** 作业名（前缀匹配，可空） */
    private String jobname;
}
