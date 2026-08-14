package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Job 元数据 DTO：描述一个可被调度执行的作业（Job）。
 * <p>
 * 由 Worker 侧扫描 {@code @ScheduleJob} 注解后构造，通过 HTTP 接口
 * {@code /open/job/register} 注册到 Admin。本 DTO <b>只承载作业元数据</b>，不携带执行器实例信息——
 * 实例注册与心跳续约由 {@link JobInstance} 经 {@code /open/job/instance/register} 独立完成
 * （见 Spec 2026-08-12 作业注册与实例注册解耦）。
 * <p>
 * {@code type}/{@code strategy} 分别对应 {@link com.wly.job.common.enumeration.JobTypeEnum}
 * 与 {@link com.wly.job.common.enumeration.ScheduleStrategyEnum} 的 code 值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobInfo {

    private String jobname;

    private String description;

    private String cron;

    private String executeParam;

    private Integer strategy;

    private Integer type;

    private String group;
}
