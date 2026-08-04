package com.wly.job.server.pojo.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 调度记录视图对象（管控后台调度记录分页查询返回）。
 *
 * <p>在调度记录实体基础上补充作业名（jobName，由查询侧反查回填）与状态描述文案（statusDesc）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleRecResp {
    private Long id;

    /** 所属作业 ID */
    private Long jobId;

    /** 作业名（查询侧回填） */
    private String jobName;

    /** 调度链路追踪请求 ID */
    private String requestId;

    /** 执行参数 */
    private String executeParam;

    /** 执行器返回的执行结果（JSON） */
    private String executeResult;

    /**
     * 执行状态（-1 失败 / 0 执行中 / 1 成功）
     * @see com.wly.job.server.enumeration.ScheduleJobStatusEnum
     */
    private Integer status;

    /** 状态描述文案 */
    private String statusDesc;

    /** 调度时间 */
    private Date scheduleTime;

    /** 完成时间 */
    private Date completeTime;

    /** 操作人 */
    private String operator;

}
