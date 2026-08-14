package com.wly.job.server.stroage;

import com.alibaba.fastjson2.JSON;
import com.wly.job.common.bean.JobInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisJobInstanceStorage} 单元测试：mock {@link StringRedisTemplate}，
 * 覆盖 list 空值过滤与 clearExpired 索引/详情对账清理。
 */
class RedisJobInstanceStorageTest {

    private static final String SERVICE = "svc";
    private static final String ALIVE_KEY = "svc:10.0.0.1:8101";
    private static final String EXPIRED_KEY = "svc:10.0.0.2:8101";
    private static final String ALIVE_JSON = JSON.toJSONString(JobInstance.builder()
            .discoveryKey(SERVICE)
            .host("10.0.0.1")
            .port(8101)
            .status(1)
            .expireTime(new Date(System.currentTimeMillis() + 60_000))
            .build());

    private StringRedisTemplate redisTemplate;
    private SetOperations<String, String> setOps;
    private ValueOperations<String, String> valueOps;
    private RedisJobInstanceStorage storage;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        storage = new RedisJobInstanceStorage(redisTemplate);
    }

    /** multiGet 结果与入参键一一对应：以键后缀区分存活/过期详情 */
    private void stubMultiGet() {
        when(valueOps.multiGet(anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            return keys.stream().map(key -> key.endsWith(EXPIRED_KEY) ? null : ALIVE_JSON).toList();
        });
    }

    @Test
    void listFiltersExpiredIndexNullsWithoutNpe() {
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE)).thenReturn(Set.of(ALIVE_KEY, EXPIRED_KEY));
        stubMultiGet();

        List<JobInstance> result = storage.list(List.of(SERVICE));

        assertEquals(1, result.size());
        assertEquals(ALIVE_KEY, result.getFirst().getInstanceKey());
    }

    @Test
    void listFiltersNullMultiGetResult() {
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE)).thenReturn(Set.of(ALIVE_KEY));
        when(valueOps.multiGet(anyList())).thenReturn(null);

        List<JobInstance> result = storage.list(List.of(SERVICE));

        assertTrue(result.isEmpty());
    }

    @Test
    void clearExpiredRemovesOnlyMembersWithNullDetail() {
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICES_KEY)).thenReturn(Set.of(SERVICE));
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE)).thenReturn(Set.of(ALIVE_KEY, EXPIRED_KEY));
        stubMultiGet();

        storage.clearExpired();

        verify(setOps).remove(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE, EXPIRED_KEY);
        verify(setOps, never()).remove(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE, ALIVE_KEY);
    }

    @Test
    void clearExpiredKeepsMembersWithPresentDetail() {
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICES_KEY)).thenReturn(Set.of(SERVICE));
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE)).thenReturn(Set.of(ALIVE_KEY));
        stubMultiGet();

        storage.clearExpired();

        verify(setOps, never()).remove(anyString(), anyString());
    }

    @Test
    void clearExpiredToleratesNullMultiGetResult() {
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICES_KEY)).thenReturn(Set.of(SERVICE));
        when(setOps.members(RedisJobInstanceStorage.JOB_SERVICE_KEY_PREFIX + SERVICE)).thenReturn(Set.of(ALIVE_KEY));
        when(valueOps.multiGet(anyList())).thenReturn(null);

        storage.clearExpired();

        verify(setOps, never()).remove(anyString(), anyString());
    }

    @Test
    void lifecycleStartThenStop() {
        storage.start();
        try {
            assertTrue(storage.isRunning());
        } finally {
            storage.stop();
        }
        assertFalse(storage.isRunning());
    }
}
