package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 作业分组分页查询条件（管控后台 /admin/group/page）。
 */
@Data
public class QueryGroupReq {

    /** 分组名（模糊匹配，可空） */
    private String groupName ;
}
