package com.wly.job.server.auth;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserInitializerTest {

    @Test
    void createsBootstrapUserWithHashedPasswordAndForcedChange() {
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        ScheduleProps props = props("admin", "initial-secret");
        when(mapper.selectByUsername("admin")).thenReturn(null);

        new AdminUserInitializer(mapper, props, new BCryptPasswordEncoder()).initialize();

        org.mockito.ArgumentCaptor<AdminUser> captor = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(mapper).insert((AdminUser) captor.capture());
        AdminUser created = captor.getValue();
        assertTrue(created.getMustChangePassword());
        assertTrue(new BCryptPasswordEncoder().matches("initial-secret", created.getPasswordHash()));
        assertFalse(created.getPasswordHash().contains("initial-secret"));
    }

    @Test
    void existingUserIsNeverOverwrittenByBootstrapConfiguration() {
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        ScheduleProps props = props("admin", "new-config-secret");
        AdminUser existing = AdminUser.builder().accountId("account-1").username("admin")
                .passwordHash("existing-hash").enabled(true).mustChangePassword(false).build();
        when(mapper.selectByUsername("admin")).thenReturn(existing);

        new AdminUserInitializer(mapper, props, new BCryptPasswordEncoder()).initialize();

        verify(mapper, never()).insert(any(AdminUser.class));
    }

    @Test
    void missingBootstrapPasswordFailsClosedWhenUserDoesNotExist() {
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        ScheduleProps props = props("admin", null);
        when(mapper.selectByUsername("admin")).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new AdminUserInitializer(mapper, props, new BCryptPasswordEncoder()).initialize());
    }

    private ScheduleProps props(String username, String password) {
        ScheduleProps props = new ScheduleProps();
        props.getAdmin().setUsername(username);
        props.getAdmin().setPassword(password);
        return props;
    }
}
