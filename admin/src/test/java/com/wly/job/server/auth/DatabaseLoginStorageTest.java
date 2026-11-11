package com.wly.job.server.auth;

import com.wly.job.server.dao.entity.AdminSession;
import com.wly.job.server.dao.mapper.AdminSessionMapper;
import com.wly.sso.core.bean.LoginInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseLoginStorageTest {

    private AdminSessionMapper mapper;
    private DatabaseLoginStorage storage;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminSessionMapper.class);
        storage = new DatabaseLoginStorage(mapper);
    }

    @Test
    void saveAndGetRoundTripCoreLoginInfo() {
        LoginInfo info = LoginInfo.builder().accountId("account-1").accountName("admin")
                .signature("signature").expireTime(System.currentTimeMillis() + 60_000)
                .roles(Map.of("account-1", List.of("ADMIN")))
                .permissions(Map.of("account-1", List.of("job:read"))).build();
        when(mapper.selectByAccountId("account-1")).thenReturn(null);

        storage.save(info);

        org.mockito.ArgumentCaptor<AdminSession> captor = org.mockito.ArgumentCaptor.forClass(AdminSession.class);
        verify(mapper).insert(captor.capture());
        AdminSession row = captor.getValue();
        assertEquals("account-1", row.getAccountId());
        assertEquals("signature", row.getSignature());
        assertEquals(info.getExpireTime(), row.getExpireTime().getTime());

        when(mapper.selectByAccountId("account-1")).thenReturn(row);
        LoginInfo loaded = storage.get("account-1");
        assertEquals("admin", loaded.getAccountName());
        assertEquals("signature", loaded.getSignature());
        assertEquals(List.of("ADMIN"), loaded.getRoles("account-1"));
    }

    @Test
    void expiredSessionIsRemovedAndRejected() {
        AdminSession expired = AdminSession.builder().accountId("account-1").accountName("admin")
                .signature("signature").expireTime(new Date(System.currentTimeMillis() - 1_000)).build();
        when(mapper.selectByAccountId("account-1")).thenReturn(expired);

        assertNull(storage.get("account-1"));
        verify(mapper).deleteByAccountId("account-1");
    }
}
