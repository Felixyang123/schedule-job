package com.wly.job.core;

import com.wly.job.core.bootstrap.JobBootstrap;
import com.wly.job.core.bootstrap.JobInstanceHandler;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.invocation.InvocationHook;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.core.registry.InnerJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import com.wly.job.core.selector.AdminNodeSelectorFactory;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
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

    private final List<InvocationHook> invocationHooks;

    private final JobBootstrap jobBootstrap;

    public ScheduleJobCoreFactory(int port,
                                  String serverAddress,
                                  String accessToken,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, null, port,
                serverAddress == null ? List.of() : List.of(serverAddress), accessToken,
                "ROUND_ROBIN", group, enableGroup, heartbeatInterval);
    }

    public ScheduleJobCoreFactory(int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, null, port, serverAddresses, accessToken, serverSelector,
                group, enableGroup, heartbeatInterval);
    }

    /**
     * 兼容构造：单地址、ROUND_ROBIN 选择器。
     */
    public ScheduleJobCoreFactory(RestClientHelper restClientHelper,
                                  InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  String serverAddress,
                                  String accessToken,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(restClientHelper, jobRegistry, remoteJobRegistry, invocationHooks, port,
                serverAddress == null ? List.of() : List.of(serverAddress), accessToken,
                "ROUND_ROBIN", group, enableGroup, heartbeatInterval);
    }

    /**
     * 兼容构造：多地址、ROUND_ROBIN 选择器（与旧 10 参签名对齐）。
     */
    public ScheduleJobCoreFactory(RestClientHelper restClientHelper,
                                  InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(restClientHelper, jobRegistry, remoteJobRegistry, invocationHooks, port,
                serverAddresses, accessToken, "ROUND_ROBIN", group, enableGroup, heartbeatInterval);
    }

    public ScheduleJobCoreFactory(RestClientHelper restClientHelper,
                                  InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        List<String> addresses = serverAddresses == null ? List.of() : serverAddresses;
        List<RestClientHelper> helpers = addresses.stream()
                .map(address -> RestClientHelper.builder().bearerToken(accessToken).baseUrl(address).build())
                .toList();
        this.restClientHelper = helpers.isEmpty() ? null : helpers.getFirst();
        this.innerJobRegistry = Optional.ofNullable(jobRegistry).orElse(new DefaultInnerJobRegistry());
        this.remoteJobRegistry = Optional.ofNullable(remoteJobRegistry)
                .orElse(new DefaultRemoteJobRegistry(helpers, AdminNodeSelectorFactory.create(serverSelector)));
        this.invocationHooks = Optional.ofNullable(invocationHooks).orElse(new ArrayList<>());
        this.port = port;
        this.groupName = group;
        this.enableGroup = enableGroup;
        this.heartbeatInterval = heartbeatInterval;

        this.jobBootstrap = new JobBootstrap(port, new JobInstanceHandler(this.innerJobRegistry));
        this.jobBootstrap.start();
    }

    public void shutdown() {
        this.jobBootstrap.shutdown();
    }

}
