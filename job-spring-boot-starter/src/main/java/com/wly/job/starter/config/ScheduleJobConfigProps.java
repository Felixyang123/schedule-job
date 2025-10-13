package com.wly.job.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "schedule-job")
@Data
public class ScheduleJobConfigProps {
    private String serverAddress;

    private String accessToken;

    private int port;

    private String group;

    private long heartbeatInterval;
}
