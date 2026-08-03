package com.wly.job.server.ha;

import com.wly.job.server.dao.mapper.ScheduleLockMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DbLeaderElectionTest {

    private final ScheduleLockMapper lockMapper = mock(ScheduleLockMapper.class);

    @Test
    void acquiresWhenRowUpdated() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(1);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        assertTrue(election.acquireOrRenew());
        verify(lockMapper).ensureLockRow();
    }

    @Test
    void staysStandbyWhenRowNotUpdated() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(0);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        assertFalse(election.acquireOrRenew());
    }

    @Test
    void ensureRowRunsOnlyOnce() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(1);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        election.acquireOrRenew();
        election.acquireOrRenew();

        verify(lockMapper, times(1)).ensureLockRow();
    }

    @Test
    void releaseDelegatesToMapper() {
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        election.release();

        verify(lockMapper).release("node-1");
    }
}
