package com.wly.job.starter.annotation;

import com.wly.job.starter.config.ScheduleJobAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(ScheduleJobAutoConfiguration.class)
public @interface EnableScheduleJob {
}
