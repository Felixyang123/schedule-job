package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.server.credential.CredentialInfo;
import com.wly.job.server.credential.CredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * 开放接口鉴权拦截器（ADR-0006 / Spec 2026-08-11 §3.1）：按「应用身份 + 环境」校验凭证。
 *
 * <p>请求以 Header 携带身份与明文凭证：
 * <pre>
 * X-Job-Group: {applicationName}
 * X-Job-Env:   {env}
 * Authorization: Bearer {明文 token}
 * </pre>
 * 校验流程：取 Header 身份 → 查凭证身份（无则 401 {@code CREDENTIAL_UNKNOWN}）→
 * 对 active / pending 两版分别用各自 salt/iterations 派生后<b>常量时间比较</b>（轮换过渡期
 * 新旧凭证均可注册）→ 命中即放行，把 {@link OpenApiAuthContext} 写入 request attribute；
 * 校验失败时<b>绕过缓存强制重载一次</b>再判定，防「新凭证生效」方向误拒（Spec §4）。
 *
 * <p><b>默认拒绝</b>：凭证身份不存在 / 未配置 seed 时一律 401（Fail-Closed，延续旧版
 * 「未配置即拒绝」语义）。失败响应为 HTTP 401 + 可区分错误码 + {@code log.warn} 记录来源 IP。
 *
 * <p>仅通过 {@link WebMvcConfig} 注册到 {@code /open/**}，不影响其它路径。
 */
@Slf4j
public class OpenApiTokenInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String HEADER_GROUP = "X-Job-Group";

    private static final String HEADER_ENV = "X-Job-Env";

    private final CredentialService credentialService;

    private final ObjectMapper objectMapper;

    public OpenApiTokenInterceptor(CredentialService credentialService, ObjectMapper objectMapper) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String applicationName = trimToNull(request.getHeader(HEADER_GROUP));
        String env = trimToNull(request.getHeader(HEADER_ENV));
        String presented = normalize(request.getHeader("Authorization"));
        if (applicationName == null || env == null) {
            return reject(request, response, "MISSING_CREDENTIAL_IDENTITY",
                    "missing X-Job-Group / X-Job-Env identity headers");
        }
        if (presented == null) {
            return reject(request, response, "MISSING_CREDENTIAL_IDENTITY", "missing Bearer token");
        }

        CredentialInfo info = credentialService.lookup(applicationName, env).orElse(null);
        if (info == null) {
            return reject(request, response, "CREDENTIAL_UNKNOWN", "credential identity not found");
        }

        Integer matchedVersion = matchVersion(info, presented);
        if (matchedVersion == null) {
            // 校验失败强制重载一次：新凭证刚生效时本节点缓存可能仍是旧值
            CredentialInfo reloaded = credentialService.lookupForced(applicationName, env).orElse(null);
            if (reloaded == null) {
                return reject(request, response, "CREDENTIAL_UNKNOWN", "credential identity not found");
            }
            matchedVersion = matchVersion(reloaded, presented);
            if (matchedVersion == null) {
                return reject(request, response, "CREDENTIAL_INVALID", "token mismatch");
            }
            info = reloaded;
        }

        request.setAttribute(OpenApiAuthContext.REQUEST_ATTRIBUTE,
                new OpenApiAuthContext(applicationName, env, matchedVersion));
        return true;
    }

    /**
     * 对 active / pending 两版派生比较，返回命中的版本号（无命中返回 null）。
     * 过期版本不参与比较（按版本行 expire_time 判定，不得用缓存 TTL 代替）。
     */
    private Integer matchVersion(CredentialInfo info, String presented) {
        Date now = new Date();
        for (CredentialInfo.VersionInfo version : new CredentialInfo.VersionInfo[]{
                info.active(), info.pending()}) {
            if (version == null) {
                continue;
            }
            if (version.expireTime() != null && now.after(version.expireTime())) {
                continue;
            }
            String derived = Pbkdf2Digest.derive(presented, version.salt(), version.iterations());
            if (Pbkdf2Digest.constantTimeEquals(version.tokenHash(), derived)) {
                return version.version();
            }
        }
        return null;
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

    private boolean reject(HttpServletRequest request, HttpServletResponse response, String code, String reason) throws Exception {
        log.warn("开放接口鉴权失败, sourceIp={}, uri={}, code={}, reason={}",
                request.getRemoteAddr(), request.getRequestURI(), code, reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, "unauthorized")));
        return false;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
