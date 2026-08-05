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
     * Admin 访问令牌（accessToken），随注册/心跳 HTTP 请求以 Bearer Token 携带
     */
    private String accessToken;

    /**
     * Worker Netty RPC 服务监听端口（Admin 据此向本实例派发任务）
     */
    private int port;

    /**
     * 任务组配置（组模式开启时，同组作业共享一个 discoveryKey 注册实例）
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
        private String name;

        private Boolean enabled;
    }
}
