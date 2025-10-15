package com.wly.job.server.config;

import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.registry.DefaultInstanceRegistry;
import com.wly.job.server.schedule.DefaultScheduleServiceImpl;
import com.wly.job.server.schedule.ScheduleService;
import com.wly.job.server.stroage.RefreshJobInstanceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduleConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScheduleService scheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, DefaultInstanceRegistry registry) {
        return new DefaultScheduleServiceImpl(client, loadBalancer, registry);
    }

    @Bean
    public DefaultInstanceRegistry defaultInstanceRegistry(RefreshJobInstanceStorage storage) {
        return new DefaultInstanceRegistry(storage);
    }
}
