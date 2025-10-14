package com.wly.job.server.schedule;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScheduleContext {
    /**
     * 实例服务发现的KEY
     */
    private String discoveryKey;

    /**
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;
}
