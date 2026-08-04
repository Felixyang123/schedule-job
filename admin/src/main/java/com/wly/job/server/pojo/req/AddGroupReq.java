package com.wly.job.server.pojo.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新增作业分组请求（管控后台 /admin/group/* 写入口）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddGroupReq {

    /** 分组名 */
    private String name;

    /** 分组描述 */
    private String description;

}
