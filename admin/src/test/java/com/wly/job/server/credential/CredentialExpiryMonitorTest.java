package com.wly.job.server.credential;

import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialExpiryMonitor} 单元测试（Spec 2026-08-14 §6 / §10 验收 9）：
 * 仅 leader 上报；≤30 天注册 Gauge（剩余天数），>30 天不设值；判定依据版本行 expire_time。
 */
class CredentialExpiryMonitorTest {

    private CredentialRep credentialRep;
    private MetricsRegistry metrics;
    private ScheduleLeaderElector leaderElector;
    private CredentialExpiryMonitor monitor;

    @BeforeEach
    void setUp() {
        credentialRep = mock(CredentialRep.class);
        metrics = mock(MetricsRegistry.class);
        leaderElector = mock(ScheduleLeaderElector.class);
        monitor = new CredentialExpiryMonitor(credentialRep, metrics, leaderElector);
    }

    private static CredentialVersion activeVersion(Long credentialId, int version, Date expireTime) {
        CredentialVersion v = new CredentialVersion();
        v.setCredentialId(credentialId);
        v.setVersion(version);
        v.setStatus(CredentialVersion.STATUS_ACTIVE);
        v.setExpireTime(expireTime);
        return v;
    }

    @Test
    void reportsOnlyWhenLeader() {
        when(leaderElector.isLeader()).thenReturn(false);
        monitor.scanOnce();
        verify(metrics, never()).gauge(any(String.class), any(String[].class), any());
    }

    @Test
    void registersGaugeForVersionWithin30Days() {
        when(leaderElector.isLeader()).thenReturn(true);
        Credential identity = new Credential();
        identity.setId(1L);
        identity.setApplicationName("payment");
        identity.setEnv("prod");
        when(credentialRep.listActiveVersions()).thenReturn(List.of(
                activeVersion(1L, 2, new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)))));
        when(credentialRep.findIdentityById(1L)).thenReturn(identity);

        monitor.scanOnce();

        ArgumentCaptor<String[]> tagsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(metrics).gauge(eq(MetricsRegistry.JOB_CREDENTIAL_EXPIRING), tagsCaptor.capture(), any());
        String[] tags = tagsCaptor.getValue();
        assertEquals("application", tags[0]);
        assertEquals("payment", tags[1]);
        assertEquals("env", tags[2]);
        assertEquals("prod", tags[3]);
        assertEquals("version", tags[4]);
        assertEquals("2", tags[5]);
    }

    @Test
    void doesNotReportWhenMoreThan30Days() {
        when(leaderElector.isLeader()).thenReturn(true);
        when(credentialRep.listActiveVersions()).thenReturn(List.of(
                activeVersion(1L, 1, new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(60)))));
        when(credentialRep.findIdentityById(1L)).thenReturn(
                credentialIdentity(1L, "payment", "prod"));

        monitor.scanOnce();

        verify(metrics, never()).gauge(any(String.class), any(String[].class), any());
    }

    private static Credential credentialIdentity(Long id, String app, String env) {
        Credential identity = new Credential();
        identity.setId(id);
        identity.setApplicationName(app);
        identity.setEnv(env);
        return identity;
    }
}
