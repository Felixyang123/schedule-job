package com.wly.job.starter.config;

import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.starter.processor.ScheduleJobAnnotationProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * 调度框架 Spring Boot 自动配置：将 {@code schedule-job.*} 配置项装配为
 * {@link ScheduleJobCoreFactory}（Worker 核心工厂），并注册
 * {@link ScheduleJobAnnotationProcessor} 负责扫描 {@code @ScheduleJob} 方法与生命周期管理。
 * <p>
 * 通过 {@link EnableScheduleJob} 注解导入，核心工厂 Bean 支持被应用自定义 Bean 覆盖
 *（@ConditionalOnMissingBean）。
 */
@Configuration
@EnableConfigurationProperties(ScheduleJobConfigProps.class)
public class ScheduleJobAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScheduleJobCoreFactory scheduleJobCoreFactory(ScheduleJobConfigProps props) {
        return new ScheduleJobCoreFactory(
                props.getPort(),
                props.getServerAddress(),
                props.getAccessToken(),
                props.getServerSelector(),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getName).orElse(null),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getEnabled).orElse(null),
                props.getHeartbeatInterval()
        );
    }

    @Bean
    public ScheduleJobAnnotationProcessor scheduleJobAnnotationProcessor(ScheduleJobCoreFactory factory) {
        return new ScheduleJobAnnotationProcessor(factory);
    }
}
