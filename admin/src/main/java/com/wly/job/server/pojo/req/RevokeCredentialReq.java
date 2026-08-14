package com.wly.job.server.pojo.req;

import lombok.Data;

/**
 * 凭证 revoke 请求（Spec 2026-08-14 §4.5）：紧急吊销。
 * <p>立即失效并置 active 指针为空（Fail-Closed，无可用版本时 /open/** 一律 401）；reason 必填。
 */
@Data
public class RevokeCredentialReq {

    /** 应用身份 */
    private String applicationName;

    /** 环境 */
    private String env;

    /** 吊销原因（必填） */
    private String reason;
}
