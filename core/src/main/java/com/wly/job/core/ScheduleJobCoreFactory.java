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

/**
 * Worker 侧核心工厂：聚合组装 Worker SDK 的各核心组件并启动 Netty RPC 服务。
 * <p>
 * 职责：
 * <ul>
 *   <li>本地任务注册表 {@link InnerJobRegistry}（默认 {@link DefaultInnerJobRegistry}）；</li>
 *   <li>远程注册 {@link RemoteJobRegistry}（默认 {@link DefaultRemoteJobRegistry}，
 *       按 serverSelector 创建 Admin 节点选择器，支持多 Admin 地址故障转移）；</li>
 *   <li>Netty TCP 服务 {@link JobBootstrap}（构造即启动，供 Admin 派发调度命令）；</li>
 *   <li>按 serverAddress 列表构建各 Admin 地址的 {@link RestClientHelper}（携带 accessToken）。</li>
 * </ul>
 * 由 job-spring-boot-starter 的自动配置或手工 new 创建；{@link #shutdown} 关闭 RPC 服务。
 */
@Getter
public class ScheduleJobCoreFactory {

    private final InnerJobRegistry innerJobRegistry;

    private final RemoteJobRegistry remoteJobRegistry;

    private final int port;

    private final String groupName;

    private final Boolean enableGroup;

    private final long heartbeatInterval;

    private final List<InvocationHook> invocationHooks;

    private final JobBootstrap jobBootstrap;

    /**
     * 便捷构造：按 serverAddresses 列表自动构建 Admin 节点选择器与 HTTP 客户端。
     */
    public ScheduleJobCoreFactory(int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  int httpConnectTimeout,
                                  int httpReadTimeout,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, port, serverAddresses, accessToken,
                httpConnectTimeout, httpReadTimeout, serverSelector,
                group, enableGroup, heartbeatInterval);
    }

    /**
     * 全参构造：可注入自定义注册表 / 远程注册器 / 调用钩子。
     * HTTP 客户端始终按 {@code serverAddresses} 列表重建（携带 accessToken）。
     */
    public ScheduleJobCoreFactory(InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  int httpConnectTimeout,
                                  int httpReadTimeout,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        List<String> addresses = serverAddresses == null ? List.of() : serverAddresses;
        List<RestClientHelper> helpers = addresses.stream()
                .map(address -> RestClientHelper.builder()
                        .bearerToken(accessToken)
                        .baseUrl(address)
                        .connectTimeout(httpConnectTimeout)
                        .readTimeout(httpReadTimeout)
                        .build())
                .toList();
        this.innerJobRegistry = Optional.ofNullable(jobRegistry).orElse(new DefaultInnerJobRegistry());
        this.remoteJobRegistry = Optional.ofNullable(remoteJobRegistry)
                .orElse(new DefaultRemoteJobRegistry(helpers, AdminNodeSelectorFactory.create(serverSelector)));
        this.invocationHooks = Optional.ofNullable(invocationHooks).orElse(new ArrayList<>());
        this.port = port;
        this.groupName = group;
        this.enableGroup = enableGroup;
        this.heartbeatInterval = heartbeatInterval;

        this.jobBootstrap = new JobBootstrap(port, new JobInstanceHandler(this.innerJobRegistry, accessToken));
        this.jobBootstrap.start();
    }

    public void shutdown() {
        this.jobBootstrap.shutdown();
    }

}
