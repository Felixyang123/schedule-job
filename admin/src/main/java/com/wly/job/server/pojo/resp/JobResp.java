package com.wly.job.server.pojo.resp;

import com.wly.job.server.enumeration.JobStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作业视图对象（管控后台作业分页查询返回）。
 *
 * <p>在作业实体基础上补充枚举描述文案（statusDesc / typeDesc / strategyDesc），便于前端直接展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobResp {
    private Long id;

    /** 作业分组名 */
    private String groupName;

    /** 作业唯一标识名 */
    private String name;

    /** 作业描述 */
    private String description;

    /** 执行参数 */
    private String executeParam;

    /** Cron 调度表达式 */
    private String cron;

    /**
     * 状态
     * 0: 停止
     * 1: 运行
     * @see JobStatusEnum
     */
    private Integer status;

    /** 状态描述文案 */
    private String statusDesc;

    /**
     * 任务类型
     * 0: 普通任务
     * 1: 单次任务
     *
     * @see com.wly.job.common.enumeration.JobTypeEnum
     */
    private Integer type;

    /** 任务类型描述文案 */
    private String typeDesc;

    /**
     * 调度策略
     *
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;

    /** 调度策略描述文案 */
    private String strategyDesc;

    /** 单次任务业务终态标记（0 未完成 / 1 已完成，仅对 type=1 有效） */
    private Integer finished;

}
