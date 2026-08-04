package com.wly.job.server.pojo.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 作业分组视图对象（管控后台分组分页查询返回）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupResp {

    private Long id;

    /** 分组名 */
    private String name;

    /** 分组描述 */
    private String description;

    private Date createTime;

    private String creator;
}
