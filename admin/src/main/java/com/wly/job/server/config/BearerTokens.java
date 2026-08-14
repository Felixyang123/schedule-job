package com.wly.job.server.config;

/**
 * Bearer Token 解析工具：统一「Authorization: Bearer {token}」的剥离逻辑，
 * 供 {@link OpenApiTokenInterceptor}（/open/** 凭证鉴权）与
 * {@link AdminAuthInterceptor}（/admin/** 会话鉴权）复用。
 */
public final class BearerTokens {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokens() {
    }

    /**
     * 提取 token：剥离大小写不敏感的 {@code Bearer } 前缀；无前缀时按裸 token 接受。
     *
     * @return 规范化后的 token；缺头 / 空值返回 null
     */
    public static String normalize(String authorization) {
        if (authorization == null) {
            return null;
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        return token.isEmpty() ? null : token;
    }
}
