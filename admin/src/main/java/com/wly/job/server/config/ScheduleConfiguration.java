package com.wly.job.server.config;

import com.wly.config.core.client.RegistryClient;
import com.wly.config.core.client.RegistryHelper;
import com.wly.config.core.config.RegistryClientProps;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.registry.DefaultInstanceRegistry;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.registry.RemoteRegisterCenterRegistry;
import com.wly.job.server.schedule.DefaultScheduleServiceImpl;
import com.wly.job.server.schedule.GroupNameDiscoveryScheduleService;
import com.wly.job.server.schedule.engine.DelayQueueSchedulerEngine;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.schedule.engine.TimeWheelSchedulerEngine;
import com.wly.job.server.stroage.JobInstancePersistStorage;
import com.wly.job.server.stroage.LocalCacheJobInstanceStorage;
import com.wly.job.server.stroage.RedisJobInstanceStorage;
import com.wly.job.server.stroage.RefreshJobInstanceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduleConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "refreshStorage", havingValue = "LOCAL")
    public RefreshJobInstanceStorage refreshLocalCacheJobInstanceStorage(JobInstancePersistStorage persistStorage,
                                                               LocalCacheJobInstanceStorage cacheStorage) {
        return new RefreshJobInstanceStorage(persistStorage, cacheStorage);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "refreshStorage", havingValue = "REDIS")
    public RefreshJobInstanceStorage refreshRedisJobInstanceStorage(JobInstancePersistStorage persistStorage,
                                                               RedisJobInstanceStorage cacheStorage) {
        return new RefreshJobInstanceStorage(persistStorage, cacheStorage);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "DEFAULT", matchIfMissing = true)
    public DefaultScheduleServiceImpl defaultScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        return new DefaultScheduleServiceImpl(client, loadBalancer, registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "GROUP")
    public GroupNameDiscoveryScheduleService groupNameDiscoveryScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        return new GroupNameDiscoveryScheduleService(client, loadBalancer, registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "registry", havingValue = "DEFAULT", matchIfMissing = true)
    public DefaultInstanceRegistry defaultInstanceRegistry(JobInstancePersistStorage storage, ScheduleProps props) {
        return new DefaultInstanceRegistry(storage, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "registry", havingValue = "CENTER")
    public RemoteRegisterCenterRegistry remoteRegisterCenterRegistry(RegistryHelper registryHelper,
                                                                     RegistryClient registryClient,
                                                                     RegistryClientProps registryClientProps,
                                                                     ScheduleProps scheduleProps) {
        return new RemoteRegisterCenterRegistry(registryHelper, registryClient, registryClientProps, scheduleProps);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "engine", havingValue = "DELAY_QUEUE", matchIfMissing = true)
    public SchedulerEngine delayQueueSchedulerEngine() {
        return new DelayQueueSchedulerEngine();
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "engine", havingValue = "TIME_WHEEL")
    public SchedulerEngine timeWheelSchedulerEngine() {
        return new TimeWheelSchedulerEngine();
    }

    @Bean
    @ConditionalOnMissingBean(SchedulerEngine.class)
    public SchedulerEngine fallbackSchedulerEngine() {
        return new DelayQueueSchedulerEngine();
    }
}
