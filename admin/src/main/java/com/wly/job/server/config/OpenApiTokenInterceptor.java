package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 开放接口鉴权拦截器：校验 {@code /open/**}（作业注册、实例心跳）携带的 token。
 *
 * <p>token 取自 {@code Authorization: Bearer {token}}（也接受裸 token，前缀匹配大小写不敏感），
 * 与 {@code schedule.access-token} 一致才放行。
 *
 * <p><b>默认拒绝</b>：{@code schedule.access-token} 未配置时一律返回 401，强制显式配置，
 * 杜绝"忘配即裸奔"。失败响应为 HTTP 401 + {@code Result.fail("unauthorized")}，
 * 并 {@code log.warn} 记录来源 IP 便于发现攻击尝试。
 *
 * <p>仅通过 {@link WebMvcConfig} 注册到 {@code /open/**}，不影响其它路径。
 */
@Slf4j
@RequiredArgsConstructor
public class OpenApiTokenInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_MESSAGE = "unauthorized";

    private final ScheduleProps scheduleProps;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String accessToken = scheduleProps.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return reject(request, response, "schedule.access-token 未配置，开放接口默认拒绝访问");
        }

        String presented = normalize(request.getHeader("Authorization"));
        if (presented == null
                || !MessageDigest.isEqual(
                        accessToken.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8))) {
            return reject(request, response, "token 不匹配");
        }
        return true;
    }

    /**
     * 提取 token：剥离大小写不敏感的 {@code Bearer } 前缀；无前缀时按裸 token 接受。
     */
    static String normalize(String authorization) {
        if (authorization == null) {
            return null;
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        return token.isEmpty() ? null : token;
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response, String reason) throws Exception {
        log.warn("开放接口鉴权失败, sourceIp={}, uri={}, reason={}",
                request.getRemoteAddr(), request.getRequestURI(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(UNAUTHORIZED_MESSAGE)));
        return false;
    }
}
