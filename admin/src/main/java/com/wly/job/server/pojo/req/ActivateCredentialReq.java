package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 凭证 activate 请求（Spec 2026-08-14 §4.3）：轮换生效。
 * <p>{@code force=true} 跳过部署就绪校验，但必须填写 reason（记入 {@code forced_activation} 审计）。
 */
@Data
public class ActivateCredentialReq {

    /** 应用身份 */
    private String applicationName;

    /** 环境 */
    private String env;

    /** 是否强制激活（跳过部署就绪校验），默认 false */
    private boolean force;

    /** 原因（force=true 时必填） */
    private String reason;
}
