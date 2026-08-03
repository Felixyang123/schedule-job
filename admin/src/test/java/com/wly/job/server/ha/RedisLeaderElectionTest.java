package com.wly.job.server.ha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedisLeaderElectionTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisLeaderElection election;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        election = new RedisLeaderElection(redisTemplate, "node-1", 10);
    }

    @Test
    void acquiresWithSetIfAbsent() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(true);

        assertTrue(election.acquireOrRenew());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void renewsWhenStillOwner() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-1");

        assertTrue(election.acquireOrRenew());
        verify(redisTemplate).expire("schedule:ha:leader", 10, TimeUnit.SECONDS);
    }

    @Test
    void rejectedWhenOwnedByOther() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-2");

        assertFalse(election.acquireOrRenew());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void releaseOnlyDeletesOwnedLock() {
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-1");
        election.release();
        verify(redisTemplate).delete("schedule:ha:leader");

        when(valueOps.get("schedule:ha:leader")).thenReturn("node-2");
        election.release();
        verify(redisTemplate, times(1)).delete("schedule:ha:leader");
    }
}
