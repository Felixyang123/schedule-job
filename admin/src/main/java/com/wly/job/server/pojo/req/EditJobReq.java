package com.wly.job.server.pojo.req;

import com.wly.job.server.enumeration.JobStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编辑作业请求（管控后台 /admin/job/edit）。
 *
 * <p>仅携带需要更新的非空字段；将单次任务（type=1）改为普通任务（type=0）时，
 * 服务端同事务重置 finished=0 以维护 Finished 不变量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EditJobReq {

    /** 作业 ID（必填） */
    private Long id;

    /** 作业描述 */
    private String description;

    /** 执行参数 */
    private String executeParam;

    /** Cron 调度表达式（非空时须通过合法性校验） */
    private String cron;

    /**
     * 状态
     * 0: 停止
     * 1: 运行
     * @see JobStatusEnum
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
}
