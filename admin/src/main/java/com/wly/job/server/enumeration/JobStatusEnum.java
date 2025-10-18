package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum JobStatusEnum {
    STOPPED(0, "停止"),
    RUNNING(1, "运行中");

    private final Integer code;

    private final String description;

    public static JobStatusEnum getByCode(Integer code) {
        for (JobStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    public static String getDescription(Integer code) {
        JobStatusEnum statusEnum = getByCode(code);
        return statusEnum != null ? statusEnum.getDescription() : null;
    }
}
