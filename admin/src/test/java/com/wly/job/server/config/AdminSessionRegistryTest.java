package com.wly.job.server.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminSessionRegistry} 单元测试（Spec 2026-08-14 §3）：
 * 签发 / 校验滑动续期 / 过期失效 / 注销 / token 随机性。
 */
class AdminSessionRegistryTest {

    private AdminSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AdminSessionRegistry();
    }

    @Test
    void createReturnsSessionWithTokenAndUsername() {
        AdminSessionRegistry.Session session = registry.create("admin");

        assertNotNull(session.token());
        assertEquals("admin", session.username());
        assertTrue(session.expireAt() > System.currentTimeMillis());
    }

    @Test
    void tokensAreRandom() {
        assertNotEquals(registry.create("admin").token(), registry.create("admin").token());
    }

    @Test
    void validateHitsAndRefreshesExpiry() throws InterruptedException {
        AdminSessionRegistry.Session session = registry.create("admin");
        long originalExpiry = session.expireAt();

        // 等待 1ms 后校验命中 → 滑动续期（expireAt 后移）
        Thread.sleep(2);
        Optional<AdminSessionRegistry.Session> hit = registry.validate(session.token());

        assertTrue(hit.isPresent());
        assertEquals("admin", hit.get().username());
        assertTrue(hit.get().expireAt() > originalExpiry, "命中后应滑动续期");
    }

    @Test
    void validateReturnsEmptyForUnknownToken() {
        assertTrue(registry.validate("unknown").isEmpty());
    }

    @Test
    void validateReturnsEmptyForExpiredSession() throws Exception {
        // 反射注入一个已过期的会话（TTL 耗尽语义），validate 应判失效并移除
        java.lang.reflect.Field field = AdminSessionRegistry.class.getDeclaredField("sessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, AdminSessionRegistry.Session> sessions =
                (java.util.Map<String, AdminSessionRegistry.Session>) field.get(registry);
        sessions.put("expired-token", new AdminSessionRegistry.Session(
                "expired-token", "admin", System.currentTimeMillis() - 1000));

        assertTrue(registry.validate("expired-token").isEmpty());
    }

    @Test
    void removeInvalidatesSession() {
        AdminSessionRegistry.Session session = registry.create("admin");

        registry.remove(session.token());

        assertFalse(registry.validate(session.token()).isPresent());
    }
}
