package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.session.UserSessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAuthInterceptor} 单元测试（Spec 2026-08-14 §3 / §10 验收 1~2）：
 * 未配置密码 Fail-Closed、缺 token / 无效 token 401、有效 token 注入 UserSessionContext 并在完成后清理。
 */
class AdminAuthInterceptorTest {

    private AdminSessionRegistry sessionRegistry;
    private ScheduleProps props;
    private AdminAuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(AdminSessionRegistry.class);
        props = new ScheduleProps();
        interceptor = new AdminAuthInterceptor(sessionRegistry, new ObjectMapper(), props);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        UserSessionContext.clear();
    }

    @Test
    void rejectsAllWhenPasswordNotConfigured() throws Exception {
        props.getAdmin().setPassword(null);

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("CREDENTIAL_AUTH_REQUIRED"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        props.getAdmin().setPassword("secret");

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        props.getAdmin().setPassword("secret");
        when(sessionRegistry.validate("bad-token")).thenReturn(java.util.Optional.empty());
        request.addHeader("Authorization", "Bearer bad-token");

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsValidTokenAndInjectsSession() throws Exception {
        props.getAdmin().setPassword("secret");
        when(sessionRegistry.validate("good-token")).thenReturn(java.util.Optional.of(
                new AdminSessionRegistry.Session("good-token", "admin", Long.MAX_VALUE)));
        request.addHeader("Authorization", "Bearer good-token");

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals("admin", UserSessionContext.getUserName());

        interceptor.afterCompletion(request, response, null, null);
        assertNull(UserSessionContext.getUserName(), "afterCompletion 必须清理会话上下文");
    }

    @Test
    void acceptsBareTokenWithoutBearerPrefix() throws Exception {
        props.getAdmin().setPassword("secret");
        when(sessionRegistry.validate("good-token")).thenReturn(java.util.Optional.of(
                new AdminSessionRegistry.Session("good-token", "admin", Long.MAX_VALUE)));
        request.addHeader("Authorization", "good-token");

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals("admin", UserSessionContext.getUserName());
    }
}
