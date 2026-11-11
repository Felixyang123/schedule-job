package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminAuthEntityTest {

    @Test
    void adminUserMapsLocalAccountFieldsWithoutPlaintextPassword() throws Exception {
        assertEquals("admin_user", AdminUser.class.getAnnotation(TableName.class).value());
        assertNotNull(AdminUser.class.getDeclaredField("accountId"));
        assertNotNull(AdminUser.class.getDeclaredField("username"));
        assertNotNull(AdminUser.class.getDeclaredField("passwordHash"));
        assertNotNull(AdminUser.class.getDeclaredField("mustChangePassword"));
        assertNoField(AdminUser.class, "password");
    }

    @Test
    void adminSessionMapsPersistentCoreLoginInfo() throws Exception {
        assertEquals("admin_session", AdminSession.class.getAnnotation(TableName.class).value());
        assertNotNull(AdminSession.class.getDeclaredField("accountId"));
        assertNotNull(AdminSession.class.getDeclaredField("signature"));
        assertNotNull(AdminSession.class.getDeclaredField("expireTime"));
        assertNotNull(AdminSession.class.getDeclaredField("rolesJson"));
        assertNotNull(AdminSession.class.getDeclaredField("permissionsJson"));
    }

    private static void assertNoField(Class<?> type, String name) {
        try {
            Field ignored = type.getDeclaredField(name);
            throw new AssertionError("plaintext credential field must not exist: " + ignored);
        } catch (NoSuchFieldException expected) {
            // expected
        }
    }
}
