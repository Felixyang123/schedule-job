package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 凭证 cancel 请求（Spec 2026-08-14 §4.4）：取消待激活（PENDING）。
 * <p>只影响 PENDING，不影响 ACTIVE；reason 必填（过期 PENDING 不自动清理，必须人工 cancel）。
 */
@Data
public class CancelCredentialReq {

    /** 应用身份 */
    private String applicationName;

    /** 环境 */
    private String env;

    /** 取消原因（必填） */
    private String reason;
}
