package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Job 元数据 DTO：描述一个可被调度执行的作业（Job）。
 * <p>
 * 由 Worker 侧扫描 {@code @ScheduleJob} 注解后构造，通过 HTTP 接口
 * {@code /open/job/register} 注册到 Admin；其中 {@code instance} 携带本执行器实例信息，
 * {@code type}/{@code strategy} 分别对应 {@link com.wly.job.common.enumeration.JobTypeEnum}
 * 与 {@link com.wly.job.common.enumeration.ScheduleStrategyEnum} 的 code 值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobInfo {

    private JobInstance instance;

    private String jobname;

    private String description;

    private String cron;

    private String executeParam;

    private Integer strategy;

    private Integer type;

    private String group;
}
