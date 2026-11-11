package com.wly.job.server.controller;

import com.wly.job.common.bean.Result;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.auth.AdminUserService;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.sso.core.config.LoginPropsConfig;
import com.wly.sso.core.handler.LoginHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AdminUserService userService = mock(AdminUserService.class);
    private final LoginHandler loginHandler = mock(LoginHandler.class);
    private final LoginPropsConfig loginProps = new LoginPropsConfig();
    private final AuthController controller = new AuthController(userService, loginHandler, loginProps);

    @AfterEach
    void tearDown() {
        UserSessionContext.clear();
    }

    @Test
    void loginUsesDatabaseUserAndCoreLoginHandler() {
        AdminUser user = AdminUser.builder().accountId("account-1").username("admin")
                .mustChangePassword(true).enabled(true).build();
        when(userService.authenticate("admin", "initial-secret")).thenReturn(user);
        when(loginHandler.login(any())).thenReturn("jwt-token");
        loginProps.setTokenTimeout(60_000);

        AuthController.LoginResp resp = controller.login(new AuthController.LoginReq("admin", "initial-secret"),
                new MockHttpServletRequest()).getData();

        assertEquals("jwt-token", resp.getToken());
        assertEquals("admin", resp.getUsername());
        assertTrue(resp.isMustChangePassword());
        verify(loginHandler).login(any());
    }

    @Test
    void logoutDelegatesToCoreAndChangePasswordUsesSessionAccount() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer jwt-token");
        Result<Void> logout = controller.logout(request);
        assertTrue(logout.getSuccess());
        verify(loginHandler).logout(any());

        UserSessionContext.setUserSession(UserSession.builder().userId("account-1").username("admin").build());
        AuthController.PasswordChangeReq change = new AuthController.PasswordChangeReq("old-secret", "new-secret");
        Result<Void> result = controller.changePassword(change);
        assertTrue(result.getSuccess());
        verify(userService).changePassword("account-1", "old-secret", "new-secret");
    }
}
