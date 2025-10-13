package com.wly.job.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum JobTypeEnum {
    GENERAL(0, "普通任务"),
    SINGLE(1, "单次任务");

    private final int code;

    private final String description;
}
