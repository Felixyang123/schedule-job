package com.wly.job.starter.config;

import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.starter.processor.ScheduleJobAnnotationProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

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
