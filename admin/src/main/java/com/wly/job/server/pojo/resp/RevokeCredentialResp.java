package com.wly.job.server.pojo.resp;

/**
 * 凭证 revoke 响应（Spec 2026-08-14 §4.5）：紧急吊销结果。
 */
public record RevokeCredentialResp(
        String applicationName,
        String env,
        int revokedVersion) {
}
