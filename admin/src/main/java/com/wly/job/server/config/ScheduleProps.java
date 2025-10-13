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
}
