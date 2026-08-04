package com.wly.job.server.stroage;

import com.alibaba.fastjson2.JSON;
import com.wly.job.common.bean.JobInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 执行器实例存储（record 形式）：多 Admin 集群共享的实例存储实现。
 *
 * <p>数据结构：
 * <ul>
 *   <li>实例详情：{@code job:instance:{instanceKey}} 字符串（JSON 序列化，TTL = 心跳到期剩余时长，自动过期）；</li>
 *   <li>服务索引：{@code job:services} Set 维护全部发现键；{@code job:service:{discoveryKey}} Set 维护
 *       发现键下的实例键集合。</li>
 * </ul>
 * <p>基于 Redis 的 TTL 实现心跳自动失效，无需后台清理线程；list 依赖 Set 索引 + multiGet 批量读取。
 */
@Component
public record RedisJobInstanceStorage(StringRedisTemplate redisTemplate) implements Storage<JobInstance> {

    /** 实例详情键前缀 */
    private static final String JOB_INSTANCE_PREFIX = "job:instance:";

    /** 全量发现键集合键 */
    private static final String JOB_SERVICES_KEY = "job:services";

    /** 单个发现键下的实例索引键前缀 */
    private static final String JOB_SERVICE_KEY_PREFIX = "job:service:";

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    /** 幂等写入：写实例详情（TTL 至心跳到期）并维护两级 Set 索引 */
    @Override
    public void put(JobInstance value) {
        redisTemplate.opsForValue().set(JOB_INSTANCE_PREFIX + value.getInstanceKey(), serialize(value), calculateTimeout(value.getExpireTime()), TimeUnit.MILLISECONDS);

        redisTemplate.opsForSet().add(JOB_SERVICES_KEY, value.getDiscoveryKey());

        redisTemplate.opsForSet().add(JOB_SERVICE_KEY_PREFIX + value.getDiscoveryKey(), value.getInstanceKey());
    }

    /** 计算实例详情键的剩余 TTL（毫秒） */
    private long calculateTimeout(Date expireTime) {
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

    /** 仅新增：语义上等价于幂等写入 */
    @Override
    public void add(JobInstance value) {
        put(value);
    }

    /** 批量仅新增 */
    @Override
    public void addAll(Collection<JobInstance> values) {
        putAll(values);
    }

    /** 按发现键集合批量拉取实例（索引取实例键 → multiGet 批量反序列化） */
    @Override
    public List<JobInstance> list(Collection<String> keys) {
        List<JobInstance> instances = new ArrayList<>();
        for (String key : keys) {
            Set<String> instanceKeys = redisTemplate.opsForSet().members(JOB_SERVICE_KEY_PREFIX + key);

            if (instanceKeys != null) {
                List<String> rawInstanceKeys = instanceKeys.stream().map(instanceKey -> JOB_INSTANCE_PREFIX + instanceKey).toList();

                List<String> instanceJsons = redisTemplate.opsForValue().multiGet(rawInstanceKeys);

                if (instanceJsons != null) {
                    List<JobInstance> instances4Key = instanceJsons.stream().map(this::deserialize).toList();

                    instances.addAll(instances4Key);
                }
            }
        }
        return instances;
    }

    private String serialize(JobInstance jobInstance) {
        return JSON.toJSONString(jobInstance);
    }

    private JobInstance deserialize(String json) {
        return JSON.parseObject(json, JobInstance.class);
    }
}
