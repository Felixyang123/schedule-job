package com.wly.job.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 作业类型枚举（对应表 {@code job.type}）：
 * GENERAL(0) 普通任务：按 Cron 周期性调度，执行完成后重新计算下次执行时间；
 * SINGLE(1)  单次任务：仅执行一次，执行成功后置业务终态 Finished（finished=1）。
 */
@AllArgsConstructor
@Getter
public enum JobTypeEnum {
    GENERAL(0, "普通任务"),
    SINGLE(1, "单次任务");

    private final int code;

    private final String description;

    public static String getDescription(int code) {
        for (JobTypeEnum value : values()) {
            if (value.code == code) {
                return value.description;
            }
        }
        return null;
    }
}
