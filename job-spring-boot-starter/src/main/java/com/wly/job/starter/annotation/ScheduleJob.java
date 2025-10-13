package com.wly.job.starter.annotation;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;

import java.lang.annotation.*;

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
