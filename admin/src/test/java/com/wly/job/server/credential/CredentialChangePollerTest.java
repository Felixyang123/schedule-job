package com.wly.job.server.credential;

import com.wly.job.server.dao.entity.CredentialChange;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialChangePoller} 单元测试（Spec 2026-08-14 §5 / §10 验收 8）：
 * 消费推进水印并失效本节点缓存；记录清理每 300 次扫描触发且仅 leader 执行。
 */
class CredentialChangePollerTest {

    private CredentialRep credentialRep;
    private CredentialService credentialService;
    private ScheduleLeaderElector leaderElector;
    private CredentialChangePoller poller;

    @BeforeEach
    void setUp() {
        credentialRep = mock(CredentialRep.class);
        credentialService = mock(CredentialService.class);
        leaderElector = mock(ScheduleLeaderElector.class);
        poller = new CredentialChangePoller(credentialRep, credentialService, leaderElector);
    }

    private static CredentialChange change(long id, String app, String env) {
        CredentialChange change = new CredentialChange();
        change.setId(id);
        change.setApplicationName(app);
        change.setEnv(env);
        return change;
    }

    @Test
    void startInitializesWatermarkToMaxId() {
        when(credentialRep.maxChangeId()).thenReturn(42L);

        poller.start();
        try {
            verify(credentialRep).maxChangeId();
        } finally {
            poller.stop();
        }
    }

    @Test
    void consumeInvalidatesEachChangeAndAdvancesWatermark() {
        when(credentialRep.listChangesAfter(0L, 100)).thenReturn(List.of(
                change(1L, "payment", "prod"), change(2L, "order", "dev")));
        when(credentialRep.listChangesAfter(2L, 100)).thenReturn(List.of());

        poller.pollOnce();
        // 第二轮从推进后的水印继续（验证水印推进到批次最大 id）
        poller.pollOnce();

        verify(credentialService).invalidate("payment", "prod");
        verify(credentialService).invalidate("order", "dev");
        verify(credentialRep).listChangesAfter(2L, 100);
    }

    @Test
    void singleFailureIsIsolated() {
        when(credentialRep.listChangesAfter(0L, 100)).thenReturn(List.of(
                change(1L, "payment", "prod"), change(2L, "order", "dev")));
        when(credentialRep.listChangesAfter(2L, 100)).thenReturn(List.of());
        // 第一条失效抛异常：不阻断整批，水印仍推进
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .doNothing().when(credentialService).invalidate("payment", "prod");

        poller.pollOnce();
        poller.pollOnce();

        verify(credentialService).invalidate("order", "dev");
        verify(credentialRep).listChangesAfter(2L, 100);
    }

    @Test
    void cleanupRunsOnlyEvery300ScansAndOnlyWhenLeader() {
        when(credentialRep.maxChangeId()).thenReturn(0L);
        when(credentialRep.listChangesAfter(any(), eq(100))).thenReturn(List.of());
        when(leaderElector.isLeader()).thenReturn(true);

        // 前 299 次不清理
        for (int i = 0; i < 299; i++) {
            poller.pollOnce();
        }
        verify(credentialRep, never()).deleteChangesBefore(any());

        // 第 300 次触发清理（leader）
        poller.pollOnce();
        verify(credentialRep).deleteChangesBefore(any());

        // 非 leader 不清理
        when(leaderElector.isLeader()).thenReturn(false);
        for (int i = 0; i < 300; i++) {
            poller.pollOnce();
        }
        org.mockito.Mockito.reset(credentialRep);
        when(credentialRep.listChangesAfter(any(), eq(100))).thenReturn(List.of());
        poller.pollOnce();
        verify(credentialRep, never()).deleteChangesBefore(any());
    }
}
