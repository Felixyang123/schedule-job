package com.wly.job.server.credential;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialChange;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.dao.rep.InstanceRep;
import com.wly.job.server.pojo.resp.ActivateCredentialResp;
import com.wly.job.server.pojo.resp.CancelCredentialResp;
import com.wly.job.server.pojo.resp.PrepareCredentialResp;
import com.wly.job.server.pojo.resp.RevokeCredentialResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialService} 管理接口测试（Spec 2026-08-14 §4 / §10 验收 3~10）：
 * prepare 状态机与明文一次、activate CAS/就绪/force、cancel/revoke、错误码、操作人来源。
 */
class CredentialManagementServiceTest {

    private static final String APP = "payment";
    private static final String ENV = "prod";
    private static final String OPERATOR = "admin";

    private CredentialRep credentialRep;
    private InstanceRep instanceRep;
    private CredentialService service;

    @BeforeEach
    void setUp() {
        credentialRep = mock(CredentialRep.class);
        instanceRep = mock(InstanceRep.class);
        service = new CredentialService(credentialRep, new ScheduleProps(), instanceRep);
        UserSessionContext.setUserSession(UserSession.builder().userId(OPERATOR).username(OPERATOR).build());
    }

    @AfterEach
    void tearDown() {
        UserSessionContext.clear();
    }

    // ---------- prepare ----------

    @Test
    void prepareCreatesActiveWhenIdentityAbsentAndReturnsPlaintextOnce() {
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(null);
        when(credentialRep.insertIdentityIfAbsent(any(Credential.class))).thenReturn(1);
        when(credentialRep.listVersions(any())).thenReturn(Collections.emptyList());
        when(credentialRep.updateActiveIfNull(any(), anyInt(), any(), any())).thenReturn(1);

        PrepareCredentialResp resp = service.prepare(APP, ENV, null);

        assertEquals(1, resp.version());
        assertTrue(resp.plaintext().startsWith("sj_"), "明文必须返回且仅此一次");
        assertNotNull(resp.maskedToken());
        assertEquals(90, daysBetween(resp.expireTime(), new Date()), "缺省有效期 90 天");

        // 直接 ACTIVE：指针 CAS 走 updateActiveIfNull
        verify(credentialRep).updateActiveIfNull(any(), eq(1), any(), eq(OPERATOR));
        // 审计操作人来自 UserSessionContext，非请求体
        verify(credentialRep).recordChange(APP, ENV, CredentialChange.CHANGE_PREPARE, OPERATOR);
    }

    @Test
    void prepareCreatesPendingWhenActiveExists() {
        Credential identity = identity(1L, 1, null);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.listVersions(1L)).thenReturn(List.of(version(1, CredentialVersion.STATUS_ACTIVE)));
        when(credentialRep.updatePendingIfNull(eq(1L), eq(2), any(), eq(OPERATOR))).thenReturn(1);

        PrepareCredentialResp resp = service.prepare(APP, ENV, 30);

        assertEquals(2, resp.version());
        assertEquals(30, daysBetween(resp.expireTime(), new Date()));
        verify(credentialRep).updatePendingIfNull(eq(1L), eq(2), any(), eq(OPERATOR));
    }

    @Test
    void prepareRejectsWhenPendingExists() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.prepare(APP, ENV, null));

        assertEquals(CredentialService.ERR_PENDING_EXISTS, e.getErrorCode());
        verify(credentialRep, never()).insertVersion(any());
    }

    @Test
    void prepareRejectsInvalidExpiry() {
        assertThrows(ScheduleException.class, () -> service.prepare(APP, ENV, 0));
        assertThrows(ScheduleException.class, () -> service.prepare(APP, ENV, 366));
    }

    @Test
    void prepareRejectsWhenCasConflict() {
        Credential identity = identity(1L, 1, null);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.listVersions(1L)).thenReturn(Collections.emptyList());
        when(credentialRep.updatePendingIfNull(any(), anyInt(), any(), any())).thenReturn(0);

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.prepare(APP, ENV, null));

        assertEquals(CredentialService.ERR_PENDING_EXISTS, e.getErrorCode());
    }

    @Test
    void prepareRequiresLogin() {
        UserSessionContext.clear();
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity(1L, 1, null));

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.prepare(APP, ENV, null));

        assertEquals(CredentialService.ERR_AUTH_REQUIRED, e.getErrorCode());
    }

    // ---------- activate ----------

    @Test
    void activateSucceedsWhenAllOnlineInstancesOnPendingVersion() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.activateVersion(eq(1L), eq(2), eq(OPERATOR), any(), anyBoolean(), any())).thenReturn(1);
        when(credentialRep.revokeActiveVersion(eq(1L), eq(1), eq(OPERATOR), any(), any())).thenReturn(1);
        when(credentialRep.activatePointer(eq(1L), eq(2), eq(2), any(), eq(OPERATOR))).thenReturn(1);
        // 在线实例均已用 pending 版本心跳 → 就绪
        when(instanceRep.list(any(Wrapper.class))).thenReturn(List.of(
                onlineInstance("10.0.0.1", 8101, 2), onlineInstance("10.0.0.2", 8101, 2)));

        ActivateCredentialResp resp = service.activate(APP, ENV, false, null);

        assertEquals(2, resp.activatedVersion());
        assertEquals(1, resp.revokedVersion());
        verify(credentialRep).recordChange(APP, ENV, CredentialChange.CHANGE_ACTIVATE, OPERATOR);
    }

    @Test
    void activateRejectsWhenNotReady() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(instanceRep.list(any(Wrapper.class))).thenReturn(List.of(onlineInstance("10.0.0.1", 8101, 1)));

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.activate(APP, ENV, false, null));

        assertEquals(CredentialService.ERR_NOT_READY, e.getErrorCode());
        verify(credentialRep, never()).activateVersion(any(), anyInt(), any(), any(), anyBoolean(), any());
    }

    @Test
    void activateForceSkipsReadyCheckAndRequiresReason() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.activateVersion(eq(1L), eq(2), eq(OPERATOR), any(), anyBoolean(), any())).thenReturn(1);
        when(credentialRep.activatePointer(eq(1L), eq(2), eq(2), any(), eq(OPERATOR))).thenReturn(1);

        // force + reason → 成功
        ActivateCredentialResp resp = service.activate(APP, ENV, true, "emergency rotation");
        assertEquals(2, resp.activatedVersion());

        // force 无 reason → 拒绝
        ScheduleException e = assertThrows(ScheduleException.class, () -> service.activate(APP, ENV, true, null));
        assertEquals(CredentialService.ERR_REASON_REQUIRED, e.getErrorCode());
    }

    @Test
    void activateRejectsNoPending() {
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity(1L, 1, null));

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.activate(APP, ENV, false, null));

        assertEquals(CredentialService.ERR_NO_PENDING, e.getErrorCode());
    }

    @Test
    void activateRejectsWhenVersionCasConflict() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(instanceRep.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(credentialRep.activateVersion(any(), anyInt(), any(), any(), anyBoolean(), any())).thenReturn(0);

        ScheduleException e = assertThrows(ScheduleException.class, () -> service.activate(APP, ENV, false, null));

        assertEquals(CredentialService.ERR_VERSION_MISMATCH, e.getErrorCode());
    }

    // ---------- cancel ----------

    @Test
    void cancelOnlyAffectsPending() {
        Credential identity = identity(1L, 1, 2);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.cancelPendingVersion(eq(1L), eq(2), eq(OPERATOR), any(), any())).thenReturn(1);
        when(credentialRep.clearPendingPointer(eq(1L), eq(2), any(), eq(OPERATOR))).thenReturn(1);

        CancelCredentialResp resp = service.cancel(APP, ENV, "rollback");

        assertEquals(2, resp.canceledVersion());
        verify(credentialRep).recordChange(APP, ENV, CredentialChange.CHANGE_CANCEL, OPERATOR);
    }

    @Test
    void cancelRejectsWithoutReasonOrPending() {
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity(1L, 1, null));

        ScheduleException noReason = assertThrows(ScheduleException.class, () -> service.cancel(APP, ENV, null));
        assertEquals(CredentialService.ERR_REASON_REQUIRED, noReason.getErrorCode());

        ScheduleException noPending = assertThrows(ScheduleException.class, () -> service.cancel(APP, ENV, "x"));
        assertEquals(CredentialService.ERR_NO_PENDING, noPending.getErrorCode());
    }

    // ---------- revoke ----------

    @Test
    void revokeImmediatelyInvalidatesActive() {
        Credential identity = identity(1L, 1, null);
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity);
        when(credentialRep.revokeActiveVersion(eq(1L), eq(1), eq(OPERATOR), any(), any())).thenReturn(1);
        when(credentialRep.clearActivePointer(eq(1L), eq(1), any(), eq(OPERATOR))).thenReturn(1);

        RevokeCredentialResp resp = service.revoke(APP, ENV, "leaked");

        assertEquals(1, resp.revokedVersion());
        // Fail-Closed：active 指针置空（后续 /open/** 一律 401）
        verify(credentialRep).clearActivePointer(eq(1L), eq(1), any(), eq(OPERATOR));
        verify(credentialRep).recordChange(APP, ENV, CredentialChange.CHANGE_REVOKE, OPERATOR);
    }

    @Test
    void revokeRejectsNoActiveOrNoReason() {
        when(credentialRep.findByIdentity(APP, ENV)).thenReturn(identity(1L, null, null));

        ScheduleException noActive = assertThrows(ScheduleException.class, () -> service.revoke(APP, ENV, "x"));
        assertEquals(CredentialService.ERR_NO_ACTIVE, noActive.getErrorCode());

        ScheduleException noReason = assertThrows(ScheduleException.class, () -> service.revoke(APP, ENV, null));
        assertEquals(CredentialService.ERR_REASON_REQUIRED, noReason.getErrorCode());
    }

    // ---------- helpers ----------

    private static Credential identity(Long id, Integer activeVersion, Integer pendingVersion) {
        Credential identity = new Credential();
        identity.setId(id);
        identity.setApplicationName(APP);
        identity.setEnv(ENV);
        identity.setActiveVersion(activeVersion);
        identity.setPendingVersion(pendingVersion);
        return identity;
    }

    private static CredentialVersion version(int version, int status) {
        CredentialVersion v = new CredentialVersion();
        v.setVersion(version);
        v.setStatus(status);
        return v;
    }

    private static Instance onlineInstance(String host, int port, Integer credentialVersion) {
        Instance instance = new Instance();
        instance.setHost(host);
        instance.setPort(port);
        instance.setStatus(Instance.ONLINE);
        instance.setApplicationName(APP);
        instance.setEnv(ENV);
        instance.setCredentialVersion(credentialVersion);
        return instance;
    }

    private static long daysBetween(Date later, Date earlier) {
        return Math.round((later.getTime() - earlier.getTime()) / 86_400_000.0);
    }
}
