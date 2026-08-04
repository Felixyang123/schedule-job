package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "job_change")
public class JobChange {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Integer changeType;

    private String operator;

    private String requestId;

    private String jobName;

    private Date createTime;
}
