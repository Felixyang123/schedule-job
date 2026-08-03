package com.wly.job.server.ha;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Redis 锁实现（schedule.ha.election=REDIS 时启用）：SET NX PX 抢锁 + 持有者续约，见 ADR-0004。
 */
@RequiredArgsConstructor
public class RedisLeaderElection implements LeaderElection {

    private static final String LOCK_KEY = "schedule:ha:leader";

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
        if (owner.equals(redisTemplate.opsForValue().get(LOCK_KEY))) {
            redisTemplate.expire(LOCK_KEY, leaseSeconds, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    @Override
    public void release() {
        if (owner.equals(redisTemplate.opsForValue().get(LOCK_KEY))) {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
