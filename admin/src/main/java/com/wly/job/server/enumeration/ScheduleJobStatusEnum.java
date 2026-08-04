package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 调度记录状态枚举（schedule_rec.status）。
 *
 * <p>-1 失败 / 0 运行中（RUNNING，唯一非终态）/ 1 成功。RUNNING 超过请求超时宽限后由
 * 主节点常驻清扫置为 FAIL（ADR-0001 / ADR-0003 的 At-Least-Once 语义）。
 */
@AllArgsConstructor
@Getter
public enum ScheduleJobStatusEnum {
    /** 失败（终态） */
    FAIL(-1, "失败"),
    /** 运行中 / RUNNING（非终态） */
    PENDING(0, "运行中"),
    /** 成功（终态） */
    SUCCESS(1, "成功");

    private final Integer code;

    private final String description;

    /** 按状态码查找枚举（未知码返回 null） */
    public static ScheduleJobStatusEnum getByCode(Integer code) {
        for (ScheduleJobStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /** 按状态码取描述文案（未知码返回 null） */
    public static String getDescription(Integer code) {
        ScheduleJobStatusEnum statusEnum = getByCode(code);
        return statusEnum != null ? statusEnum.getDescription() : null;
    }
}
