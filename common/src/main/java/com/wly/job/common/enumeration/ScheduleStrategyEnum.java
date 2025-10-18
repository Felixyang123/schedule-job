package com.wly.job.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ScheduleStrategyEnum {
    RANDOM(1, "随机"),

    ROUND_ROBIN(2, "轮询"),

    HASH(3, "哈希");

    private final Integer code;

    private final String description;

    public static String getDescription(Integer code) {
        for (ScheduleStrategyEnum value : ScheduleStrategyEnum.values()) {
            if (value.code.equals(code)) {
                return value.description;
            }
        }
        return null;
    }
}
