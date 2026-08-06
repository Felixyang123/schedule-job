package com.wly.job.server.stroage;

import com.alibaba.fastjson2.JSON;
import com.wly.job.common.bean.JobInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis 执行器实例存储：多 Admin 集群共享的实例存储实现。
 *
 * <p>数据结构：
 * <ul>
 *   <li>实例详情：{@code job:instance:{instanceKey}} 字符串（JSON 序列化，TTL = 心跳到期剩余时长，自动过期）；</li>
 *   <li>服务索引：{@code job:services} Set 维护全部发现键；{@code job:service:{discoveryKey}} Set 维护
 *       发现键下的实例键集合。</li>
 * </ul>
 * <p>基于 Redis 的 TTL 实现心跳自动失效；list 依赖 Set 索引 + multiGet 批量读取。
 * 详情键 TTL 过期后服务索引 Set 会残留脏键，由 {@link #clearExpired} 周期对账清理：
 * 以详情为权威，仅当实例详情不存在（null）时才摘除索引成员，读路径不删索引（避免与续租竞态）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RedisJobInstanceStorage implements CacheStorage<JobInstance>, SmartLifecycle {

    /** 实例详情键前缀 */
    private static final String JOB_INSTANCE_PREFIX = "job:instance:";

    /** 全量发现键集合键 */
    private static final String JOB_SERVICES_KEY = "job:services";

    /** 单个发现键下的实例索引键前缀 */
    private static final String JOB_SERVICE_KEY_PREFIX = "job:service:";

    /** 周期对账清理间隔（秒），与 {@link LocalCacheJobInstanceStorage} 对齐 */
    private static final long CLEAR_EXPIRED_INTERVAL_SECONDS = 30L;

    private final StringRedisTemplate redisTemplate;

    private ScheduledExecutorService clearExpiredExecutor;

    private volatile boolean running = false;

    /** 幂等写入：写实例详情（TTL 至心跳到期）并维护两级 Set 索引 */
    @Override
    public void put(JobInstance value) {
        redisTemplate.opsForValue().set(JOB_INSTANCE_PREFIX + value.getInstanceKey(), serialize(value), calculateTimeout(value.getExpireTime()), TimeUnit.MILLISECONDS);

        redisTemplate.opsForSet().add(JOB_SERVICES_KEY, value.getDiscoveryKey());

        redisTemplate.opsForSet().add(JOB_SERVICE_KEY_PREFIX + value.getDiscoveryKey(), value.getInstanceKey());
    }

    /** 计算实例详情键的剩余 TTL（毫秒）；expireTime 缺失时按立即过期处理（0），与 {@link JobInstance#isExpired} 行为对齐 */
    private long calculateTimeout(Date expireTime) {
        if (expireTime == null) {
            return 0L;
        }
        return expireTime.getTime() - System.currentTimeMillis();
    }

    /** 批量幂等写入 */
    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    /** 移除实例：删除详情键并摘除服务索引 */
    @Override
    public void remove(JobInstance value) {
        redisTemplate.delete(JOB_INSTANCE_PREFIX + value.getInstanceKey());

        redisTemplate.opsForSet().remove(JOB_SERVICE_KEY_PREFIX + value.getDiscoveryKey(), value.getInstanceKey());
    }

    /** 清空全部实例数据（先按服务索引逐个删除详情，再清理索引键） */
    @Override
    public void clear() {
        Set<String> services = redisTemplate.opsForSet().members(JOB_SERVICES_KEY);

        if (services == null) {
            return;
        }

        for (String service : services) {
            Set<String> instances = redisTemplate.opsForSet().members(JOB_SERVICE_KEY_PREFIX + service);
            if (instances != null) {
                for (String instance : instances) {
                    redisTemplate.delete(JOB_INSTANCE_PREFIX + instance);
                }
                redisTemplate.delete(JOB_SERVICE_KEY_PREFIX + service);
            }
        }
        redisTemplate.delete(JOB_SERVICES_KEY);
    }

    /** 按发现键集合批量拉取实例（索引取实例键 → multiGet 批量反序列化；详情已过期的索引条目返回 null，直接过滤） */
    @Override
    public List<JobInstance> list(Collection<String> keys) {
        List<JobInstance> instances = new ArrayList<>();
        for (String key : keys) {
            Set<String> instanceKeys = redisTemplate.opsForSet().members(JOB_SERVICE_KEY_PREFIX + key);

            if (instanceKeys != null) {
                List<String> rawInstanceKeys = instanceKeys.stream().map(instanceKey -> JOB_INSTANCE_PREFIX + instanceKey).toList();

                List<String> instanceJsons = redisTemplate.opsForValue().multiGet(rawInstanceKeys);

                if (instanceJsons != null) {
                    List<JobInstance> instances4Key = instanceJsons.stream()
                            .filter(Objects::nonNull)
                            .map(this::deserialize)
                            .toList();

                    instances.addAll(instances4Key);
                }
            }
        }
        return instances;
    }

    /** 周期对账清理：扫描各服务索引成员，实例详情已过期（multiGet 为 null）才摘除索引成员——详情为权威，读路径不删索引 */
    @Override
    public void clearExpired() {
        try {
            doClearExpired();
        } catch (Exception e) {
            // 后台周期任务不得因单次 Redis 异常中断（scheduleWithFixedDelay 遇异常会永久停止后续执行）
            log.warn("RedisJobInstanceStorage.clearExpired failed, will retry next round.", e);
        }
    }

    private void doClearExpired() {
        Set<String> services = redisTemplate.opsForSet().members(JOB_SERVICES_KEY);

        if (services == null) {
            return;
        }

        for (String service : services) {
            Set<String> instanceKeys = redisTemplate.opsForSet().members(JOB_SERVICE_KEY_PREFIX + service);

            if (instanceKeys == null || instanceKeys.isEmpty()) {
                continue;
            }

            List<String> memberKeys = instanceKeys.stream().toList();
            List<String> rawInstanceKeys = memberKeys.stream().map(instanceKey -> JOB_INSTANCE_PREFIX + instanceKey).toList();

            List<String> instanceJsons = redisTemplate.opsForValue().multiGet(rawInstanceKeys);

            if (instanceJsons == null) {
                continue;
            }

            // multiGet 结果顺序与入参键顺序一一对应，为 null 即详情键已过期
            for (int i = 0; i < memberKeys.size(); i++) {
                if (instanceJsons.get(i) == null) {
                    redisTemplate.opsForSet().remove(JOB_SERVICE_KEY_PREFIX + service, memberKeys.get(i));
                }
            }
        }
    }

    private String serialize(JobInstance jobInstance) {
        return JSON.toJSONString(jobInstance);
    }

    private JobInstance deserialize(String json) {
        return JSON.parseObject(json, JobInstance.class);
    }

    /** 启动后台过期对账线程（30s 固定延迟），与 {@link LocalCacheJobInstanceStorage} 对齐 */
    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;
        clearExpiredExecutor = Executors.newSingleThreadScheduledExecutor();
        clearExpiredExecutor.scheduleWithFixedDelay(this::clearExpired, CLEAR_EXPIRED_INTERVAL_SECONDS, CLEAR_EXPIRED_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("RedisJobInstanceStorage started, clearExpired scheduled every {}s.", CLEAR_EXPIRED_INTERVAL_SECONDS);
    }

    /** 优雅停止：置 running=false 并关闭后台线程池 */
    @Override
    public void stop() {
        this.running = false;
        if (clearExpiredExecutor != null) {
            clearExpiredExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
