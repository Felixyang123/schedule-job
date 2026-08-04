package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 作业管理态枚举（job.status）。
 *
 * <p>0 停止 / 1 运行，用于控制作业是否参与调度；区别于单次任务的业务终态 finished
 * （管理态与业务终态解耦）。
 */
@AllArgsConstructor
@Getter
public enum JobStatusEnum {
    /** 停止 */
    STOPPED(0, "停止"),
    /** 运行中 */
    RUNNING(1, "运行中");

    private final Integer code;

    private final String description;

    /** 按状态码查找枚举（未知码返回 null） */
    public static JobStatusEnum getByCode(Integer code) {
        for (JobStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /** 按状态码取描述文案（未知码返回 null） */
    public static String getDescription(Integer code) {
        JobStatusEnum statusEnum = getByCode(code);
        return statusEnum != null ? statusEnum.getDescription() : null;
    }
}
