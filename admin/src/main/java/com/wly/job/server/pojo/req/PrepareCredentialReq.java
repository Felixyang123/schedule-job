package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 凭证 prepare 请求（Spec 2026-08-14 §4.2）：创建 / 轮换准备。
 * <p>操作人不走请求体（一律取 {@code UserSessionContext}，ADR 决策 #21）。
 */
@Data
public class PrepareCredentialReq {

    /** 应用身份 */
    private String applicationName;

    /** 环境 */
    private String env;

    /** 有效期天数（缺省 90，>365 或 ≤0 拒绝） */
    private Integer expireDays;
}
