package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 作业分组实体，对应 {@code job_group} 表。
 *
 * <p>作业分组的元数据载体，用于按业务线 / 团队组织执行器与作业；作业注册与调度时以分组名
 * （groupName）关联。当前分组表仅承载分组名与描述，不参与调度决策。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "job_group", autoResultMap = true)
public class JobGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组名（作业注册时引用，构成 job 表唯一键的一部分） */
    private String name;

    /** 分组描述 */
    private String description;

    private Date createTime;

    private String creator;
}
