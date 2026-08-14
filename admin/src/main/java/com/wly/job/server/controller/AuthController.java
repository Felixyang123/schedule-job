package com.wly.job.server.controller;

import com.wly.job.common.bean.Result;
import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.server.config.AdminSessionRegistry;
import com.wly.job.server.config.BearerTokens;
import com.wly.job.server.config.ScheduleProps;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管控后台登录 / 登出（Spec 2026-08-14 §3）：配置化单用户。
 *
 * <p>密码由 {@code schedule.admin.username} / {@code schedule.admin.password} 配置（明文交付，
 * 与 credential-seed 同模型），校验为<b>常量时间比较</b>（防时序侧信道）。登录成功签发进程内
 * 会话 token（{@link AdminSessionRegistry}），后续 {@code /admin/**} 请求以
 * {@code Authorization: Bearer {token}} 携带。
 *
 * <p>未配置密码时本接口同样拒绝（Fail-Closed，与 {@code /admin/**} 鉴权一致）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AdminSessionRegistry sessionRegistry;

    private final ScheduleProps props;

    @PostMapping("/login")
    public Result<LoginResp> login(@RequestBody LoginReq req, HttpServletRequest request) {
        if (props.getAdmin().getPassword() == null || props.getAdmin().getPassword().isBlank()) {
            return unauthorized(request, "schedule.admin.password not configured");
        }
        String username = props.getAdmin().getUsername();
        boolean userMatch = constantTimeEquals(username == null ? "" : username, nullToEmpty(req.getUsername()));
        boolean passMatch = constantTimeEquals(props.getAdmin().getPassword(), nullToEmpty(req.getPassword()));
        if (!userMatch || !passMatch) {
            return unauthorized(request, "invalid credentials");
        }
        AdminSessionRegistry.Session session = sessionRegistry.create(username);
        log.info("管控后台登录成功, username={}, sourceIp={}", username, request.getRemoteAddr());
        return Result.success(new LoginResp(session.token(), username, session.expireAt()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = BearerTokens.normalize(request.getHeader("Authorization"));
        if (token != null) {
            sessionRegistry.remove(token);
        }
        return Result.success();
    }

    private Result<LoginResp> unauthorized(HttpServletRequest request, String reason) {
        log.warn("管控后台登录拒绝, sourceIp={}, reason={}", request.getRemoteAddr(), reason);
        return Result.fail("CREDENTIAL_AUTH_REQUIRED", "unauthorized");
    }

    /** 常量时间比较（防时序侧信道；配置缺失场景先判空再由 Fail-Closed 拦截） */
    private static boolean constantTimeEquals(String a, String b) {
        return Pbkdf2Digest.constantTimeEquals(a, b);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    @RequiredArgsConstructor
    public static class LoginResp {
        /** 会话 token（后续以 Authorization: Bearer 携带） */
        private final String token;
        /** 登录用户名 */
        private final String username;
        /** 会话到期时间戳（毫秒） */
        private final long expireTime;
    }
}
