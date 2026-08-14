package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管控后台会话鉴权拦截器（Spec 2026-08-14 §3）：校验 {@code /admin/**} 的登录态。
 *
 * <p>流程：读取 {@code Authorization: Bearer {token}}（剥离逻辑见 {@link BearerTokens}）→
 * 查 {@link AdminSessionRegistry}（命中滑动续期）→ 命中则写入
 * {@link UserSessionContext#setUserSession} 供后续服务取操作人（{@code UserSessionContext.getUserName()}），
 * 并在 {@code afterCompletion} 清理（防线程池复用串线）。
 *
 * <p><b>Fail-Closed</b>：未配置 {@code schedule.admin.password} 时一律 401（启动期由
 * {@code AdminSessionRegistry} 之外的配置校验逻辑 WARN），登录接口本身同样拒绝。
 *
 * <p>豁免路径（{@code /admin/auth/login}、{@code /admin/auth/logout}）在 {@link WebMvcConfig}
 * 注册时以 {@code excludePathPatterns} 排除，本拦截器不做路径判断。
 */
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final AdminSessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper;

    private final ScheduleProps props;

    public AdminAuthInterceptor(AdminSessionRegistry sessionRegistry, ObjectMapper objectMapper,
                                ScheduleProps props) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (props.getAdmin().getPassword() == null || props.getAdmin().getPassword().isBlank()) {
            return reject(request, response, "CREDENTIAL_AUTH_REQUIRED",
                    "schedule.admin.password not configured");
        }
        String token = BearerTokens.normalize(request.getHeader(HEADER_AUTHORIZATION));
        if (token == null) {
            return reject(request, response, "CREDENTIAL_AUTH_REQUIRED", "missing Bearer token");
        }
        AdminSessionRegistry.Session session = sessionRegistry.validate(token).orElse(null);
        if (session == null) {
            return reject(request, response, "CREDENTIAL_AUTH_REQUIRED", "invalid or expired session");
        }
        UserSessionContext.setUserSession(UserSession.builder()
                .userId(session.username())
                .username(session.username())
                .build());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserSessionContext.clear();
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response, String code, String reason) throws Exception {
        log.warn("管控接口鉴权失败, sourceIp={}, uri={}, code={}, reason={}",
                request.getRemoteAddr(), request.getRequestURI(), code, reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, "unauthorized")));
        return false;
    }
}
