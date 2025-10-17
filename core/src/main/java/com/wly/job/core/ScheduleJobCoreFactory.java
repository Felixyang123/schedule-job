package com.wly.job.core;

import com.wly.job.core.bootstrap.JobBootstrap;
import com.wly.job.core.bootstrap.JobInstanceHandler;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.core.registry.InnerJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import lombok.Getter;

import java.util.Optional;

@Getter
public class ScheduleJobCoreFactory {

    private final InnerJobRegistry innerJobRegistry;

    private final RemoteJobRegistry remoteJobRegistry;

    private final RestClientHelper restClientHelper;

    private final int port;

    private final String groupName;

    private final Boolean enableGroup;

    private final long heartbeatInterval;

    public ScheduleJobCoreFactory(int port, String serverAddress, String accessToken, String group, Boolean enableGroup, long heartbeatInterval) {
        this(null, null, null, port, serverAddress, accessToken, group, enableGroup, heartbeatInterval);
    }

    public ScheduleJobCoreFactory(RestClientHelper restClientHelper, InnerJobRegistry jobRegistry, RemoteJobRegistry remoteJobRegistry, int port, String serverAddress, String accessToken, String group, Boolean enableGroup, long heartbeatInterval) {
        this.restClientHelper = Optional.ofNullable(restClientHelper).orElse(RestClientHelper.builder().bearerToken(accessToken).baseUrl(serverAddress).build());
        this.innerJobRegistry = Optional.ofNullable(jobRegistry).orElse(new DefaultInnerJobRegistry());
        this.remoteJobRegistry = Optional.ofNullable(remoteJobRegistry).orElse(new DefaultRemoteJobRegistry(this.restClientHelper));
        this.port = port;
        this.groupName = group;
        this.enableGroup = enableGroup;
        this.heartbeatInterval = heartbeatInterval;

        JobBootstrap.init(port, new JobInstanceHandler(this.innerJobRegistry));
    }

}
