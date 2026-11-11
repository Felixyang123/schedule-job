package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.bean.LoginInfo;
import com.wly.sso.core.handler.LoginContext;
import com.wly.sso.core.handler.LoginHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** Bridges SSO Core's persistent session validation into the job request context. */
@Slf4j
public class JobSsoAuthInterceptor implements HandlerInterceptor {

    private final LoginHandler loginHandler;
    private final ObjectMapper objectMapper;
    private final AdminUserMapper userMapper;

    public JobSsoAuthInterceptor(LoginHandler loginHandler, ObjectMapper objectMapper,
                                 AdminUserMapper userMapper) {
        this.loginHandler = loginHandler;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = BearerTokens.normalize(request.getHeader("Authorization"));
        if (token == null) {
            return reject(response);
        }
        try {
            LoginInfo info = loginHandler.checkToken(LoginContext.builder().token(token).build());
            if (info == null || info.getAccountId() == null) {
                return reject(response);
            }
            AdminUser user = userMapper.selectByAccountId(info.getAccountId());
            boolean passwordChangeRequest = request.getRequestURI().endsWith("/auth/password/change");
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
                return reject(response);
            }
            if (Boolean.TRUE.equals(user.getMustChangePassword()) && !passwordChangeRequest) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "PASSWORD_CHANGE_REQUIRED", "password change required");
            }
            UserSessionContext.setUserSession(UserSession.builder()
                    .userId(info.getAccountId())
                    .username(info.getAccountName())
                    .build());
            return true;
        } catch (RuntimeException ex) {
            log.debug("job admin token rejected: {}", ex.getMessage());
            return reject(response);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserSessionContext.clear();
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                "CREDENTIAL_AUTH_REQUIRED", "unauthorized");
    }

    private boolean reject(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
        return false;
    }
}
