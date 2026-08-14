package com.wly.job.server.ha;

import com.wly.job.server.config.ScheduleProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ScheduleLeaderElectorTest {

    private LeaderElection election;
    private ScheduleProps props;
    private LeadershipListener listener;
    private ScheduleLeaderElector elector;

    @BeforeEach
    void setUp() {
        election = mock(LeaderElection.class);
        listener = mock(LeadershipListener.class);
        props = new ScheduleProps();
        props.getHa().setEnabled(true);
        props.getHa().setPollSeconds(1);
        props.getHa().setRenewSeconds(3);
        props.getHa().setLeaseSeconds(10);
        elector = new ScheduleLeaderElector(election, props, new CopyOnWriteArrayList<>(List.of(listener)));
    }

    @Test
    void becomeLeaderWhenAcquireSucceeds() {
        when(election.acquireOrRenew()).thenReturn(true);

        elector.tick();

        assertTrue(elector.isLeader());
        verify(listener).onBecomeLeader();
    }

    @Test
    void staysStandbyWhenAcquireFails() {
        when(election.acquireOrRenew()).thenReturn(false);

        elector.tick();

        assertFalse(elector.isLeader());
        verify(listener, never()).onBecomeLeader();
    }

    @Test
    void stepDownWhenRenewFails() {
        props.getHa().setRenewSeconds(0);
        when(election.acquireOrRenew()).thenReturn(true, true, false);

        elector.tick(); // 成为主
        assertTrue(elector.isLeader());
        elector.tick(); // 续约成功
        assertTrue(elector.isLeader());
        elector.tick(); // 续约失败 -> 让位

        assertFalse(elector.isLeader());
        verify(listener).onLoseLeadership();
    }

    @Test
    void disabledModeIsAlwaysLeader() {
        props.getHa().setEnabled(false);

        elector.start();

        assertTrue(elector.isLeader());
        verify(listener).onBecomeLeader();
        elector.stop();
    }

    @Test
    void stopReleasesLockWhenLeader() {
        when(election.acquireOrRenew()).thenReturn(true);
        elector.tick();
        assertTrue(elector.isLeader());

        elector.stop();

        verify(election).release();
        assertFalse(elector.isLeader());
    }
}
