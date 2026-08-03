package com.wly.job.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "schedule")
@Configuration
@Data
public class ScheduleProps {

    /**
     * 调度请求超时时间（毫秒）
     */
    private long reqTimeout = 30000;

    /**
     * 实例注册器类型
     * @see com.wly.job.server.enumeration.RegistryTypeEnum
     */
    private String registry;

    private Boolean enableRegisterInstance;

    /**
     * DEFAULT
     * GROUP
     */
    private String service;

    /**
     * REDIS
     * LOCAL
     */
    private String refreshStorage;

    /**
     * DELAY_QUEUE
     * TIME_WHEEL
     */
    private String engine = "DELAY_QUEUE";

    /**
     * 调度派发 worker 线程数（按 jobId 分片），默认 1
     */
    private int dispatchThreads = 1;

    /**
     * HA 单活模式开关（多 Admin 部署时开启），默认 false
     */
    private boolean haEnabled = false;

    /**
     * 选主实现: DB（默认）/ REDIS
     */
    private String haElection = "DB";

    /**
     * 租约时长（秒），默认 10
     */
    private long haLeaseSeconds = 10;

    /**
     * 续约间隔（秒），默认 3
     */
    private long haRenewSeconds = 3;

    /**
     * 选主轮询间隔（秒），默认 1
     */
    private long haPollSeconds = 1;

    /**
     * 陈旧 RUNNING 记录清扫间隔（秒），默认 30
     */
    private long haStaleSweepSeconds = 30;

    /**
     * 节点唯一 ID（默认 host:port，由 ScheduleConfiguration 组装）
     */
    private String haInstanceId;
}
