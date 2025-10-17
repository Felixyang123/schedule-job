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
import com.wly.job.server.stroage.JobInstancePersistStorage;
import com.wly.job.server.stroage.LocalCacheJobInstanceStorage;
import com.wly.job.server.stroage.RefreshJobInstanceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduleConfiguration {

    @Bean
    public RefreshJobInstanceStorage refreshJobInstanceStorage(JobInstancePersistStorage persistStorage,
                                                               LocalCacheJobInstanceStorage cacheStorage) {
        return new RefreshJobInstanceStorage(persistStorage, cacheStorage);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "DEFAULT")
    public DefaultScheduleServiceImpl defaultScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        return new DefaultScheduleServiceImpl(client, loadBalancer, registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "GROUP")
    public GroupNameDiscoveryScheduleService groupNameDiscoveryScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry) {
        return new GroupNameDiscoveryScheduleService(client, loadBalancer, registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "registry", havingValue = "DEFAULT")
    public DefaultInstanceRegistry defaultInstanceRegistry(RefreshJobInstanceStorage storage, ScheduleProps props) {
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
}
