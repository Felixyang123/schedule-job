package com.wly.job.server.credential;

import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.rep.CredentialRep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialService} 种子初始化与查询测试（ADR-0006 / Spec 验收 3）。
 */
class CredentialServiceTest {

    private CredentialRep credentialRep;
    private ScheduleProps props;
    private CredentialService service;

    @BeforeEach
    void setUp() {
        credentialRep = mock(CredentialRep.class);
        props = new ScheduleProps();
        service = new CredentialService(credentialRep, props);
    }

    // ---------- 种子初始化 ----------

    @Test
    void seedCreatesActiveV1WhenIdentityAbsent() {
        props.setCredentialSeed("payment:prod:secret-token");
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(null);
        when(credentialRep.insertIdentityIfAbsent(any(Credential.class))).thenReturn(1);

        Credential created = new Credential();
        created.setId(100L);
        created.setApplicationName("payment");
        created.setEnv("prod");
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(created);

        service.initFromSeed();

        ArgumentCaptor<CredentialVersion> captor = ArgumentCaptor.forClass(CredentialVersion.class);
        verify(credentialRep).insertVersion(captor.capture());
        CredentialVersion version = captor.getValue();
        assertEquals(CredentialVersion.STATUS_ACTIVE, version.getStatus());
        assertEquals(1, version.getVersion());
        assertEquals(100L, version.getCredentialId());
        assertFalse(version.getTokenHash().isBlank(), "token_hash 必须派生");
        assertTrue(version.getMaskedToken().contains("****"), "必须脱敏");

        // 摘要不可逆：明文不回存
        assertNotEquals("secret-token", version.getTokenHash());
        // 相同明文可派生验证
        String derived = Pbkdf2Digest.derive("secret-token", version.getSalt(), version.getIterations());
        assertEquals(version.getTokenHash(), derived, "同一明文派生应得到存储摘要");

        verify(credentialRep).updateIdentity(created);
    }

    @Test
    void seedSkipsWhenIdentityAlreadyActive() {
        props.setCredentialSeed("payment:prod:secret-token");
        Credential existing = new Credential();
        existing.setId(100L);
        existing.setActiveVersion(2);
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(existing);

        service.initFromSeed();

        verify(credentialRep, never()).insertVersion(any(CredentialVersion.class));
        verify(credentialRep, never()).updateIdentity(any(Credential.class));
    }

    @Test
    void seedMissingIsFailClosedNoOp() {
        props.setCredentialSeed(null);
        service.initFromSeed();
        verify(credentialRep, never()).insertVersion(any(CredentialVersion.class));
    }

    @Test
    void seedInvalidItemIsSkipped() {
        props.setCredentialSeed("malformed-item");
        service.initFromSeed();
        verify(credentialRep, never()).insertVersion(any(CredentialVersion.class));
    }

    // ---------- 查询 ----------

    @Test
    void lookupReturnsActiveVersion() {
        Credential identity = new Credential();
        identity.setId(1L);
        identity.setApplicationName("payment");
        identity.setEnv("prod");
        identity.setActiveVersion(1);
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(identity);

        CredentialVersion v1 = new CredentialVersion();
        v1.setCredentialId(1L);
        v1.setVersion(1);
        v1.setTokenHash("hash");
        v1.setSalt("salt");
        v1.setIterations(120_000);
        when(credentialRep.findVersion(1L, 1)).thenReturn(v1);

        Optional<CredentialInfo> result = service.lookup("payment", "prod");
        assertTrue(result.isPresent());
        assertEquals(1, result.get().active().version());
        assertEquals("hash", result.get().active().tokenHash());
        assertTrue(result.get().pending() == null);
    }

    @Test
    void lookupReturnsEmptyWhenIdentityUnknown() {
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(null);
        assertTrue(service.lookup("payment", "prod").isEmpty());
    }

    @Test
    void lookupWithBlankIdentityReturnsEmpty() {
        assertTrue(service.lookup("", "").isEmpty());
        assertTrue(service.lookup(null, "prod").isEmpty());
    }

    @Test
    void invalidateRemovesCachedEntry() {
        Credential identity = new Credential();
        identity.setId(1L);
        identity.setApplicationName("payment");
        identity.setEnv("prod");
        identity.setActiveVersion(1);
        when(credentialRep.findByIdentity("payment", "prod")).thenReturn(identity);
        CredentialVersion v1 = new CredentialVersion();
        v1.setCredentialId(1L);
        v1.setVersion(1);
        v1.setIterations(120_000);
        when(credentialRep.findVersion(1L, 1)).thenReturn(v1);

        service.lookup("payment", "prod");
        service.invalidate("payment", "prod");
        service.lookup("payment", "prod");
        // 失效后应再次查 DB（幂等可重复）
        verify(credentialRep, org.mockito.Mockito.times(2)).findByIdentity("payment", "prod");
    }
}
