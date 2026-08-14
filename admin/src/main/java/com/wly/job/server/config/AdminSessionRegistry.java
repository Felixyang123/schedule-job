package com.wly.job.server.config;

import com.wly.job.common.session.UserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管控后台会话注册表（Spec 2026-08-14 §3）：进程内 token → 会话映射。
 *
 * <p>单机内存实现（多 Admin 节点会话互不可见，本阶段接受——管理面通常单点访问）：
 * 登录成功后签发 32 字节 SecureRandom hex token，TTL 30 分钟<b>滑动续期</b>
 * （每次鉴权命中重置 expireAt），过期项懒清理。
 *
 * <p>使用方：{@link AdminAuthController}（签发 / 注销）与
 * {@link AdminAuthInterceptor}（校验并注入 {@link UserSession}）。
 */
@Slf4j
@Component
public class AdminSessionRegistry {

    /** 会话 TTL：30 分钟，滑动续期（每次命中重置） */
    static final long SESSION_TTL_MS = 30L * 60 * 1000;

    /** 懒清理触发阈值：仅当会话规模超过该值才扫描过期项（管理面规模小，避免每请求遍历） */
    private static final int LAZY_CLEAN_THRESHOLD = 512;

    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 签发新会话。
     *
     * @param username 登录用户名（即 UserSession 的 userId 与 username）
     * @return 新签发的会话
     */
    public Session create(String username) {
        if (sessions.size() > LAZY_CLEAN_THRESHOLD) {
            cleanExpired();
        }
        String token = generateToken();
        Session session = new Session(token, username, System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, session);
        return session;
    }

    /**
     * 校验会话：命中且未过期时<b>滑动续期</b>并返回；未命中 / 已过期返回空。
     */
    public Optional<Session> validate(String token) {
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (session.expireAt() <= now) {
            sessions.remove(token);
            return Optional.empty();
        }
        Session refreshed = session.refresh(now + SESSION_TTL_MS);
        sessions.put(token, refreshed);
        return Optional.of(refreshed);
    }

    /** 注销会话（登出 / 兜底清理）。 */
    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expireAt() <= now) {
                iterator.remove();
            }
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 进程内会话：token + 登录用户名 + 到期时间。
     */
    public record Session(String token, String username, long expireAt) {

        Session refresh(long newExpireAt) {
            return new Session(token, username, newExpireAt);
        }
    }
}
