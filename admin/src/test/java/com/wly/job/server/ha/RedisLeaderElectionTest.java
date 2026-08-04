package com.wly.job.server.ha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void renewsAtomicallyWhenStillOwner() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("schedule:ha:leader")),
                eq("node-1"), eq(10000L))).thenReturn(1L);

        assertTrue(election.acquireOrRenew());

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("schedule:ha:leader")), eq("node-1"), eq(10000L));
    }

    @Test
    void rejectedWhenOwnedByOther() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertFalse(election.acquireOrRenew());
    }

    @Test
    void releaseOnlyDeletesOwnedLock() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("schedule:ha:leader")),
                eq("node-1"))).thenReturn(1L);

        election.release();

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("schedule:ha:leader")), eq("node-1"));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseIsNoOpWhenNotOwner() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("schedule:ha:leader")),
                eq("node-1"))).thenReturn(0L);

        election.release();

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of("schedule:ha:leader")), eq("node-1"));
        verify(redisTemplate, never()).delete(anyString());
    }
}
