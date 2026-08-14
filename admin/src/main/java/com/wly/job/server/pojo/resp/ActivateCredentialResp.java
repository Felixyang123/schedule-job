package com.wly.job.server.pojo.resp;

/**
 * 凭证 activate 响应（Spec 2026-08-14 §4.3）：轮换生效结果。
 */
public record ActivateCredentialResp(
        String applicationName,
        String env,
        /** 新生效版本号 */
        int activatedVersion,
        /** 被吊销的旧版本号（无旧版时为 null） */
        Integer revokedVersion) {
}
