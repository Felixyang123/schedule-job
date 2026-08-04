package com.wly.job.starter.annotation;

import com.wly.job.starter.config.ScheduleJobAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 启用调度框架的总开关注解：标注在 Spring Boot 启动类上，
 * 通过 {@code @Import} 引入 {@link ScheduleJobAutoConfiguration} 完成自动装配，
 * 使 {@code @ScheduleJob} 注解的方法具备被 Admin 调度执行的能力。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(ScheduleJobAutoConfiguration.class)
public @interface EnableScheduleJob {
}
