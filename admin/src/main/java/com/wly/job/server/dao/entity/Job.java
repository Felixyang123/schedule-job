package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "job", autoResultMap = true)
public class Job {

    public static final Integer UNABLE = 0;
    public static final Integer ENABLE = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String groupName;

    private String name;

    private String description;

    private String executeParam;

    private String cron;

    /**
     * 状态
     * 0: 停止
     * 1: 运行
     */
    private Integer status;

    /**
     * 任务类型
     * 0: 普通任务
     * 1: 单次任务
     *
     * @see com.wly.job.common.enumeration.JobTypeEnum
     */
    private Integer type;

    /**
     * 调度策略
     *
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;

    /**
     * 单次任务完成标记
     * 0: 未完成 1: 已完成（终态，与管理态 status 解耦）
     */
    private Integer finished;

    private Integer deleted;

    private Date createTime;

    private Date updateTime;

    private String creator;

    private String updater;

    public Job init() {
        this.createTime = new Date();
        this.updateTime = this.createTime;
        this.deleted = 0;
        this.status = ENABLE;
        this.finished = 0;
        return this;
    }

    public boolean isEnable() {
        return Objects.equals(this.status, ENABLE);
    }
}
