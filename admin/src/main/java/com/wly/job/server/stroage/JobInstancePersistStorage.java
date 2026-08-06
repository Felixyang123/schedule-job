package com.wly.job.server.stroage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 执行器实例物理表持久化存储（record 形式）：以 {@code instance} 表为后端的 Storage 实现。
 *
 * <p>执行器心跳经此落库：{@link #put} 通过 {@code InstanceMapper.saveOrUpdate} 按唯一键幂等
 * upsert 并刷新心跳到期时间；{@link #remove} 将在线实例置为 OFFLINE（不物理删除）；
 * {@link #list} 仅返回在线且未过期的实例。多 Admin 集群下可作为实例状态共享的持久化底座，
 * 配合 {@link RefreshJobInstanceStorage} 周期性回灌本地缓存。
 */
@Component
public record JobInstancePersistStorage(InstanceRep instanceRep) implements Storage<JobInstance> {

    /** 幂等写入：转换为 Instance 后按唯一键 upsert（存在则刷新心跳，不存在则插入） */
    @Override
    public void put(JobInstance value) {
        Instance instance = JobBeanConverter.convert(value).init();
        instance.setCreator("system");
        instance.setUpdater("system");
        instanceRep.getBaseMapper().saveOrUpdate(instance);
    }

    /** 批量幂等写入 */
    @Override
    public void putAll(Collection<JobInstance> values) {
        values.forEach(this::put);
    }

    /** 注销实例：将对应在线实例置为下线（保留历史行，不物理删除） */
    @Override
    public void remove(JobInstance value) {
        instanceRep.update(Wrappers.<Instance>lambdaUpdate().set(Instance::getStatus, Instance.OFFLINE)
                .eq(Instance::getName, value.getDiscoveryKey())
                .eq(Instance::getHost, value.getHost())
                .eq(Instance::getPort, value.getPort())
                .eq(Instance::getStatus, Instance.ONLINE));
    }

    /** 物理表持久化无进程内数据可清，空实现 */
    @Override
    public void clear() {
    }

    /** 按发现键集合查询在线且未过期的实例 */
    @Override
    public List<JobInstance> list(Collection<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }

        List<Instance> instances = instanceRep.list(Wrappers.<Instance>lambdaQuery().in(Instance::getName, keys)
                .eq(Instance::getStatus, Instance.ONLINE).ge(Instance::getExpireTime, new Date()));
        return instances.stream().map(JobBeanConverter::convert).toList();
    }
}
