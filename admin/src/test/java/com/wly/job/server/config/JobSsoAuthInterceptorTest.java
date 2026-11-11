package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.bean.LoginInfo;
import com.wly.sso.core.dto.SsoErrorCode;
import com.wly.sso.core.exception.SsoException;
import com.wly.sso.core.handler.LoginContext;
import com.wly.sso.core.handler.LoginHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSsoAuthInterceptorTest {

    private LoginHandler loginHandler;
    private AdminUserMapper userMapper;
    private JobSsoAuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        loginHandler = mock(LoginHandler.class);
        userMapper = mock(AdminUserMapper.class);
        interceptor = new JobSsoAuthInterceptor(loginHandler, new ObjectMapper(), userMapper);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        UserSessionContext.clear();
    }

    @Test
    void rejectsMissingAndInvalidTokensWith401() throws Exception {
        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());

        response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer bad-token");
        when(loginHandler.checkToken(any(LoginContext.class)))
                .thenThrow(new SsoException(SsoErrorCode.TOKEN_INVALID));
        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void blocksManagementUntilFirstPasswordChange() throws Exception {
        request.setRequestURI("/admin/job/page");
        request.addHeader("Authorization", "Bearer initial-token");
        when(loginHandler.checkToken(any(LoginContext.class))).thenReturn(LoginInfo.builder()
                .accountId("account-1").accountName("admin").build());
        when(userMapper.selectByAccountId("account-1")).thenReturn(AdminUser.builder()
                .accountId("account-1").username("admin").enabled(true).mustChangePassword(true).build());

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void acceptsCoreSessionAndClearsThreadContext() throws Exception {
        request.addHeader("Authorization", "Bearer good-token");
        when(loginHandler.checkToken(any(LoginContext.class))).thenReturn(LoginInfo.builder()
                .accountId("account-1").accountName("admin").build());
        when(userMapper.selectByAccountId("account-1")).thenReturn(AdminUser.builder()
                .accountId("account-1").username("admin").enabled(true).mustChangePassword(false).build());

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals("account-1", UserSessionContext.getUserId());
        assertEquals("admin", UserSessionContext.getUserName());

        interceptor.afterCompletion(request, response, null, null);
        assertNull(UserSessionContext.getUserName());
    }
}
