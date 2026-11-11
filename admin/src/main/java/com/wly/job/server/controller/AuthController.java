package com.wly.job.server.controller;

import com.wly.job.common.bean.Result;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.auth.AdminUserService;
import com.wly.job.server.config.BearerTokens;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.sso.core.config.LoginPropsConfig;
import com.wly.sso.core.exception.SsoException;
import com.wly.sso.core.handler.LoginContext;
import com.wly.sso.core.handler.LoginHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Job Admin login, logout and first-login password rotation endpoints. */
@Slf4j
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AdminUserService userService;
    private final LoginHandler loginHandler;
    private final LoginPropsConfig loginProps;

    @PostMapping("/login")
    public Result<LoginResp> login(@RequestBody LoginReq req, HttpServletRequest request) {
        try {
            AdminUser user = userService.authenticate(req == null ? null : req.getUsername(),
                    req == null ? null : req.getPassword());
            String token = loginHandler.login(LoginContext.builder()
                    .accountId(user.getAccountId())
                    .accountName(user.getUsername())
                    .build());
            long expireTime = System.currentTimeMillis() + loginProps.getTokenTimeout();
            log.info("管控后台登录成功, username={}, sourceIp={}", user.getUsername(), request.getRemoteAddr());
            return Result.success(new LoginResp(token, user.getUsername(), expireTime,
                    Boolean.TRUE.equals(user.getMustChangePassword())));
        } catch (SsoException ex) {
            log.warn("管控后台登录拒绝, sourceIp={}, reason={}", request.getRemoteAddr(), ex.getMessage());
            return Result.fail("CREDENTIAL_AUTH_REQUIRED", "unauthorized");
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = BearerTokens.normalize(request.getHeader("Authorization"));
        if (token != null) {
            loginHandler.logout(LoginContext.builder().token(token).build());
        }
        return Result.success();
    }

    @PostMapping("/password/change")
    public Result<Void> changePassword(@RequestBody PasswordChangeReq req) {
        String accountId = UserSessionContext.getUserId();
        if (accountId == null) {
            return Result.fail("CREDENTIAL_AUTH_REQUIRED", "unauthorized");
        }
        try {
            userService.changePassword(accountId, req == null ? null : req.getOldPassword(),
                    req == null ? null : req.getNewPassword());
            return Result.success();
        } catch (SsoException ex) {
            return Result.fail(String.valueOf(ex.getErrorCode().getCode()), ex.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PasswordChangeReq {
        private String oldPassword;
        private String newPassword;
    }

    @Data
    @RequiredArgsConstructor
    public static class LoginResp {
        private final String token;
        private final String username;
        private final long expireTime;
        private final boolean mustChangePassword;
    }
}
