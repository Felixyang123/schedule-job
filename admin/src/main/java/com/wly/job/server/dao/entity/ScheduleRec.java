package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "schedule_rec", autoResultMap = true)
public class ScheduleRec {
    public static final Integer FAIL = -1;
    public static final Integer PENDING = 0;
    public static final Integer SUCCESS = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String requestId;

    private String executeParam;

    private String executeResult;

    private Integer status;

    private Date scheduleTime;

    private Date completeTime;
}
