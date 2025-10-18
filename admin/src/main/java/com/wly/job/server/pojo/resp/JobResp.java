package com.wly.job.server.pojo.resp;

import com.wly.job.server.enumeration.JobStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobResp {
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
     * @see JobStatusEnum
     */
    private Integer status;

    private String statusDesc;

    /**
     * 任务类型
     * 0: 普通任务
     * 1: 单次任务
     *
     * @see com.wly.job.common.enumeration.JobTypeEnum
     */
    private Integer type;

    private String typeDesc;

    /**
     * 调度策略
     *
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;

    private String strategyDesc;

}
