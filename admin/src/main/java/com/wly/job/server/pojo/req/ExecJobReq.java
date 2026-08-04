package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 手动执行作业请求（管控后台 /admin/job/exec）。
 *
 * <p>{@code executeParam} 可临时覆盖作业的执行参数（仅本次生效，不持久化）。
 */
@Data
public class ExecJobReq {

    /** 待执行的作业 ID（必填，须为运行中作业） */
    private Long jobId;

    /** 本次执行的临时执行参数（可空，为空时沿用作业配置） */
    private String executeParam;
}
