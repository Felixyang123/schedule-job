package com.wly.job.samples.center.config;

import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.samples.center.registry.RegisterCenterInstanceRegistry;
import com.wly.job.starter.config.ScheduleJobConfigProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class CommonConfiguration {

    @Bean
    public RestClientHelper restClientHelper(ScheduleJobConfigProps props) {
        return RestClientHelper.builder()
                .bearerToken(props.getAccessToken())
                .credentialIdentity(props.getApplicationName(), "default")
                .baseUrl(props.getServerAddress().getFirst())
                .build();
    }

    @Bean
    public DefaultRemoteJobRegistry defaultRemoteJobRegistry(RestClientHelper restClientHelper) {
        return new DefaultRemoteJobRegistry(restClientHelper);
    }

    @Bean
    public ScheduleJobCoreFactory scheduleJobCoreFactory(ScheduleJobConfigProps props,
                                                         RegisterCenterInstanceRegistry remoteJobRegistry) {
        return new ScheduleJobCoreFactory(
                null,
                remoteJobRegistry,
                null,
                props.getPort(),
                props.getServerAddress(),
                props.getAccessToken(),
                props.getApplicationName(),
                "default",
                props.getCredentialVersion(),
                (int) props.getHttpConnectTimeout(),
                (int) props.getHttpReadTimeout(),
                props.getServerSelector(),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getEnabled).orElse(null),
                props.getHeartbeatInterval());
    }

}
