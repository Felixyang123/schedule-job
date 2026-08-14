package com.wly.job.server.pojo.resp;

import java.util.Date;

/**
 * 凭证 prepare 响应（Spec 2026-08-14 §4.2）：创建 / 轮换准备结果。
 * <p><b>明文只在本次响应中出现一次</b>（ADR 决策 #7），遗失只能重新 prepare；后续查询仅出 {@code maskedToken}。
 */
public record PrepareCredentialResp(
        String applicationName,
        String env,
        int version,
        String maskedToken,
        /** 明文凭证（仅本次返回，数据库不落明文） */
        String plaintext,
        Date expireTime) {
}
