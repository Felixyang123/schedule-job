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
}
