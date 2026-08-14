package com.wly.job.server.config;

/**
 * /open/** 鉴权上下文：由 {@link OpenApiTokenInterceptor} 校验通过后写入
 * {@code request attribute}，供 Controller 二次校验与实例注册路径使用
 * （Spec 2026-08-11 §3.1：身份走 Header 而非 Body）。
 *
 * @param applicationName  鉴权命中的应用身份（Header X-Job-Group）
 * @param env              鉴权命中的环境（Header X-Job-Env）
 * @param credentialVersion 鉴权命中的凭证版本（active 或 pending）
 */
public record OpenApiAuthContext(String applicationName, String env, Integer credentialVersion) {

    public static final String REQUEST_ATTRIBUTE = OpenApiAuthContext.class.getName();
}
