package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ScheduleJobStatusEnum {
    FAIL(-1, "失败"),
    PENDING(0, "运行中"),
    SUCCESS(1, "成功");

    private final Integer code;

    private final String description;

    public static ScheduleJobStatusEnum getByCode(Integer code) {
        for (ScheduleJobStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    public static String getDescription(Integer code) {
        ScheduleJobStatusEnum statusEnum = getByCode(code);
        return statusEnum != null ? statusEnum.getDescription() : null;
    }
}
