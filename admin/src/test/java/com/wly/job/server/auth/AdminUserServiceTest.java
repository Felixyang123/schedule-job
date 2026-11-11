package com.wly.job.server.auth;

import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminSessionMapper;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private AdminUserMapper userMapper;
    private AdminSessionMapper sessionMapper;
    private AdminUserService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userMapper = org.mockito.Mockito.mock(AdminUserMapper.class);
        sessionMapper = org.mockito.Mockito.mock(AdminSessionMapper.class);
        service = new AdminUserService(userMapper, sessionMapper, encoder);
    }

    @Test
    void authenticateAcceptsEnabledUserWithMatchingPassword() {
        AdminUser user = user("account-1", "admin", "secret");
        when(userMapper.selectByUsername("admin")).thenReturn(user);

        assertEquals(user, service.authenticate("admin", "secret"));
    }

    @Test
    void authenticateRejectsUnknownDisabledOrWrongPassword() {
        when(userMapper.selectByUsername("missing")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.authenticate("missing", "secret"));

        AdminUser disabled = user("account-2", "disabled", "secret");
        disabled.setEnabled(false);
        when(userMapper.selectByUsername("disabled")).thenReturn(disabled);
        assertThrows(RuntimeException.class, () -> service.authenticate("disabled", "secret"));

        AdminUser wrong = user("account-3", "admin", "secret");
        when(userMapper.selectByUsername("admin")).thenReturn(wrong);
        assertThrows(RuntimeException.class, () -> service.authenticate("admin", "wrong"));
    }

    @Test
    void changePasswordRehashesAndInvalidatesSessions() {
        AdminUser user = user("account-1", "admin", "old-secret");
        user.setMustChangePassword(true);
        when(userMapper.selectByAccountId("account-1")).thenReturn(user);

        service.changePassword("account-1", "old-secret", "new-secret");

        org.junit.jupiter.api.Assertions.assertTrue(encoder.matches("new-secret", user.getPasswordHash()));
        org.junit.jupiter.api.Assertions.assertFalse(user.getMustChangePassword());
        verify(userMapper).updateById(user);
        verify(sessionMapper).deleteByAccountId("account-1");
    }

    @Test
    void changePasswordRejectsReusingOldPassword() {
        AdminUser user = user("account-1", "admin", "old-secret");
        when(userMapper.selectByAccountId("account-1")).thenReturn(user);

        assertThrows(RuntimeException.class,
                () -> service.changePassword("account-1", "old-secret", "old-secret"));
        verify(userMapper, never()).updateById(any(AdminUser.class));
        verify(sessionMapper, never()).deleteByAccountId("account-1");
    }

    private AdminUser user(String accountId, String username, String rawPassword) {
        return AdminUser.builder().accountId(accountId).username(username)
                .passwordHash(encoder.encode(rawPassword)).enabled(true).mustChangePassword(false).build();
    }
}
