package com.wly.job.server.pojo.resp;

/**
 * 凭证 cancel 响应（Spec 2026-08-14 §4.4）：取消待激活结果。
 */
public record CancelCredentialResp(
        String applicationName,
        String env,
        int canceledVersion) {
}
