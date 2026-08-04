package com.wly.job.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 路由策略枚举（对应表 {@code job.strategy}）：Admin 在候选 Worker 实例中挑选
 * 单个执行节点时的策略——RANDOM 随机、ROUND_ROBIN 轮询、HASH 哈希（按实例键稳定钉住）。
 */
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
