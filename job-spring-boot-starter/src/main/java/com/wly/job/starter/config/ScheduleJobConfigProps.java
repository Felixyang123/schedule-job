package com.wly.job.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Worker 侧配置属性绑定（前缀 {@code schedule-job}）：对应 core 模块
 * {@code ScheduleJobCoreFactory} 的构造参数，供自动配置装配使用。
 */
@ConfigurationProperties(prefix = "schedule-job")
@Data
public class ScheduleJobConfigProps {

    /**
     * Admin 地址列表（支持逗号分隔；单值兼容，如 http://a:8100,http://b:8100）
     */
    private List<String> serverAddress = new ArrayList<>();

    /**
     * Admin 节点选择算法: ROUND_ROBIN（默认）/ RANDOM / HASH
     */
    private String serverSelector = "ROUND_ROBIN";

    /**
     * 应用身份（凭证体系维度之一，ADR-0006）：<b>强制配置</b>，为空启动失败；
     * 随注册/心跳 HTTP 请求以 {@code X-Job-Group} 头携带，组模式下兼作实例发现键。
     */
    private String applicationName;

    /**
     * 本 Worker 所持凭证版本号（默认 1）：与 Admin 侧发放的凭证版本一致，
     * 用于 Worker 侧 RPC 验签的版本预检（Spec 2026-08-11 §3.2）。
     */
    private int credentialVersion = 1;

    /**
     * 本应用明文凭证（ADR-0006）：<b>强制配置</b>，为空启动失败。
     * 随注册/心跳 HTTP 请求以 {@code Authorization: Bearer {token}} 携带；
     * Worker 本地用它派生 HMAC 密钥验签 Admin 的 RPC 派发请求。
     */
    private String accessToken;

    /**
     * Worker Netty RPC 服务监听端口（Admin 据此向本实例派发任务）
     */
    private int port;

    /**
     * 组模式配置（组模式开启时，同应用作业共享 application-name 作为 discoveryKey 注册实例；
     * 注意 {@code group.name} 已删除——应用身份即发现键，见 ADR-0006）
     */
    private Group group;

    /**
     * 心跳续约间隔（秒），过期时间一般为间隔的 3 倍
     */
    private long heartbeatInterval = 10;

    /**
     * 注册/心跳 HTTP 连接超时（毫秒），默认 2000ms。
     * <p>
     * <b>硬约束公式</b>：单次 HTTP 尝试超时 ≤ (Admin 租约剔除时间 − 心跳间隔) / Admin 节点数。
     * 例：剔除 30s、心跳 10s（宽限 20s）、5 节点 → 单次尝试须 ≤ 4s，
     * 保证最坏轮询全部 Admin 节点后仍能在租约过期前完成续租（Spec §2.3）。
     */
    private long httpConnectTimeout = 2000;

    /**
     * 注册/心跳 HTTP 读超时（毫秒），默认 3000ms。
     * <p>
     * <b>硬约束公式</b>：单次 HTTP 尝试超时 ≤ (Admin 租约剔除时间 − 心跳间隔) / Admin 节点数。
     * 例：剔除 30s、心跳 10s（宽限 20s）、5 节点 → 单次尝试须 ≤ 4s，
     * 保证最坏轮询全部 Admin 节点后仍能在租约过期前完成续租（Spec §2.3）。
     */
    private long httpReadTimeout = 3000;

    @Data
    public static class Group {
        /** 组模式开关：为 true 时同应用作业共享 application-name 注册实例（发现键） */
        private Boolean enabled;
    }
}
