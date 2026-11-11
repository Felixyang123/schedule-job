package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
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

    public JobSsoAuthInterceptor(LoginHandler loginHandler, ObjectMapper objectMapper) {
        this.loginHandler = loginHandler;
        this.objectMapper = objectMapper;
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
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail("CREDENTIAL_AUTH_REQUIRED", "unauthorized")));
        return false;
    }
}
