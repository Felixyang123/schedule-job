package com.wly.job.starter.annotation;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;

import java.lang.annotation.*;

/**
 * 作业声明注解：标注在 Spring Bean 的方法上，将该方法声明为一个可被调度执行的 Job。
 * <p>
 * 启动时由 {@link com.wly.job.starter.processor.ScheduleJobAnnotationProcessor} 扫描并包装为
 * 本地任务，同时构造 {@code JobInfo} 注册到 Admin。方法必须声明 0 或 1 个参数（1 个参数时
 * 由 executeParam 字符串按目标类型转换注入）。
 * <p>
 * 属性约定：{@code name} 为任务唯一标识，{@code cron} 为调度表达式；
 * {@code type} 取值见 {@link JobTypeEnum}（默认普通任务），{@code strategy} 取值见
 * {@link ScheduleStrategyEnum}（默认随机）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScheduleJob {
    String name();

    String cron();

    String description() default "";

    JobTypeEnum type() default JobTypeEnum.GENERAL;

    ScheduleStrategyEnum strategy() default ScheduleStrategyEnum.RANDOM;

    String executeParam() default "";
}
