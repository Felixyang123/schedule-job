package com.wly.job.server.auth;

import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.bean.LoginInfo;
import com.wly.sso.core.config.LoginPropsConfig;
import com.wly.sso.core.handler.LoginContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseAuthHelperTest {

    @Test
    void authenticateBuildsCompleteCoreLoginInfo() {
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        AdminUser user = AdminUser.builder().accountId("account-1").username("admin")
                .enabled(true).build();
        when(mapper.selectByAccountId("account-1")).thenReturn(user);
        LoginPropsConfig props = new LoginPropsConfig();
        props.setTokenTimeout(60_000);

        LoginInfo info = new DatabaseAuthHelper(mapper, props)
                .authenticate(LoginContext.builder().accountId("account-1").build());

        assertEquals("account-1", info.getAccountId());
        assertEquals("admin", info.getAccountName());
        assertNotNull(info.getSignature());
        assertTrue(info.getExpireTime() > System.currentTimeMillis());
    }
}
