package com.wly.job.core;

import com.wly.job.core.bootstrap.JobBootstrap;
import com.wly.job.core.bootstrap.JobInstanceHandler;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.invocation.InvocationHook;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.core.registry.InnerJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import com.wly.job.core.security.RpcRequestAuthenticator;
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
 *   <li>Netty TCP 服务 {@link JobBootstrap}（构造即启动，供 Admin 派发调度命令），
 *       派发请求经 {@link RpcRequestAuthenticator} 验签（ADR-0006）；</li>
 *   <li>按 serverAddress 列表构建各 Admin 地址的 {@link RestClientHelper}
 *       （携带明文凭证 Bearer + 身份 Header）。</li>
 * </ul>
 * 由 job-spring-boot-starter 的自动配置或手工 new 创建；{@link #shutdown} 关闭 RPC 服务。
 * 保留构造器 API 以兼容 starter 与手工集成；引入 Builder 会扩大公开 API 且不能改善 Spring 配置绑定。
 */
@Getter
public class ScheduleJobCoreFactory {

    private final InnerJobRegistry innerJobRegistry;

    private final RemoteJobRegistry remoteJobRegistry;

    private final int port;

    /** 应用身份（凭证体系维度之一，组模式发现键也取它） */
    private final String applicationName;

    /** 环境（从 activeProfiles 提取，凭证体系维度之一） */
    private final String env;

    /** 本 Worker 所持凭证版本号（与 Admin 发放版本一致） */
    private final int credentialVersion;

    private final Boolean enableGroup;

    private final long heartbeatInterval;

    private final List<InvocationHook> invocationHooks;

    private final JobBootstrap jobBootstrap;

    /**
     * 便捷构造：按 serverAddresses 列表自动构建 Admin 节点选择器与 HTTP 客户端。
     *
     * @param applicationName    应用身份（凭证体系，必填非空）
     * @param env                环境（凭证体系）
     * @param credentialVersion  本 Worker 所持凭证版本（默认 1）
     */
    public ScheduleJobCoreFactory(int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String applicationName,
                                  String env,
                                  int credentialVersion,
                                  int httpConnectTimeout,
                                  int httpReadTimeout,
                                  String serverSelector,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, port, serverAddresses, accessToken, applicationName, env, credentialVersion,
                httpConnectTimeout, httpReadTimeout, serverSelector, enableGroup, heartbeatInterval);
    }

    /**
     * 全参构造：可注入自定义注册表 / 远程注册器 / 调用钩子。
     * HTTP 客户端始终按 {@code serverAddresses} 列表重建（携带明文凭证 + 身份 Header）。
     */
    public ScheduleJobCoreFactory(InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String applicationName,
                                  String env,
                                  int credentialVersion,
                                  int httpConnectTimeout,
                                  int httpReadTimeout,
                                  String serverSelector,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException(
                    "schedule-job.application-name must not be blank (credential identity)");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(
                    "schedule-job.accessToken must not be blank (plain credential)");
        }
        List<String> addresses = serverAddresses == null ? List.of() : serverAddresses;
        List<RestClientHelper> helpers = addresses.stream()
                .map(address -> RestClientHelper.builder()
                        .bearerToken(accessToken)
                        .credentialIdentity(applicationName, env)
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
        this.applicationName = applicationName;
        this.env = env;
        this.credentialVersion = credentialVersion;
        this.enableGroup = enableGroup;
        this.heartbeatInterval = heartbeatInterval;

        this.jobBootstrap = new JobBootstrap(port,
                new JobInstanceHandler(this.innerJobRegistry,
                        new RpcRequestAuthenticator(accessToken, credentialVersion)));
        this.jobBootstrap.start();
    }

    public void shutdown() {
        this.jobBootstrap.shutdown();
    }

}
