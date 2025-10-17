package com.wly.job.server.stroage;

import com.alibaba.fastjson2.JSON;
import com.wly.job.common.bean.JobInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public record RedisJobInstanceStorage(StringRedisTemplate redisTemplate) implements Storage<JobInstance> {

    private static final String JOB_INSTANCE_PREFIX = "job:instance:";

    private static final String JOB_SERVICES_KEY = "job:services";

    private static final String JOB_SERVICE_KEY_PREFIX = "job:service:";

    @Override
    public JobInstance get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void put(JobInstance value) {
        redisTemplate.opsForValue().set(JOB_INSTANCE_PREFIX + value.getInstanceKey(), serialize(value), 30, TimeUnit.SECONDS);

        redisTemplate.opsForSet().add(JOB_SERVICES_KEY, value.getDiscoveryKey());

        redisTemplate.opsForSet().add(JOB_SERVICE_KEY_PREFIX + value.getDiscoveryKey(), value.getInstanceKey());
    }

    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    @Override
    public void remove(JobInstance value) {
        redisTemplate.delete(JOB_INSTANCE_PREFIX + value.getInstanceKey());

        redisTemplate.opsForSet().remove(JOB_SERVICE_KEY_PREFIX + value.getDiscoveryKey(), value.getInstanceKey());
    }

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

    @Override
    public void add(JobInstance value) {
        put(value);
    }

    @Override
    public void addAll(Collection<JobInstance> values) {
        putAll(values);
    }

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
