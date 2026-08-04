package com.wly.job.server.ha;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 锁实现（schedule.ha.election=REDIS 时启用）：SET NX PX 抢锁 + 持有者续约，见 ADR-0004。
 */
@RequiredArgsConstructor
public class RedisLeaderElection implements LeaderElection {

    private static final String LOCK_KEY = "schedule:ha:leader";

    /**
     * 原子续约：仅当当前持有者仍是自己时才延长 TTL。
     * 避免"读到旧值 -> 锁过期 -> 他人抢到 -> 自己续约他人锁"的双主竞态。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            else
                return 0
            end
            """, Long.class);

    /**
     * 原子释放：仅当当前持有者仍是自己时才删除，避免误删他人锁。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    private final String owner;

    private final long leaseSeconds;

    @Override
    public boolean acquireOrRenew() {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, owner, leaseSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            return true;
        }
        Long renewed = redisTemplate.execute(RENEW_SCRIPT, List.of(LOCK_KEY), owner, leaseSeconds * 1000L);
        return renewed != null && renewed == 1L;
    }

    @Override
    public void release() {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(LOCK_KEY), owner);
    }
}
