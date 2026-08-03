package com.wly.job.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    private String accessToken;

    private int port;

    private Group group;

    private long heartbeatInterval = 10;

    @Data
    public static class Group {
        private String name;

        private Boolean enabled;
    }
}
