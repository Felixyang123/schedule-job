package com.wly.job.server.registry;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.stroage.Storage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 默认执行器实例注册中心：基于本地 {@link Storage} 的进程内实现（record 形式）。
 *
 * <p>执行器心跳上报时，以作业发现键为维度将实例写入 {@code Storage}（LocalCache / Redis / DB）；
 * 调度派发前经 {@link #discover} 拉取候选节点。写入受 {@code schedule.enable-register-instance}
 * 开关控制；开关未开启时 register 直接返回 false。
 */
@Slf4j
public record DefaultInstanceRegistry(Storage<JobInstance> instanceStorage, ScheduleProps props) implements Registry {
    /** 按作业发现键拉取当前可用执行器实例（候选节点，供负载均衡选择） */
    @Override
    public List<JobInstance> discover(String name) {
        // 发现键为空（如 GROUP 模式下作业未配置分组）时视为无候选，避免 List.of(null) 抛 NPE
        if (name == null) {
            return List.of();
        }
        return instanceStorage.list(List.of(name));
    }

    /** 注册 / 刷新执行器实例：置为在线并写入存储；受实例注册开关控制 */
    @Override
    public boolean register(JobInstance jobInstance) {
        if (!Boolean.TRUE.equals(props.getEnableRegisterInstance())) {
            return false;
        }
        jobInstance.setStatus(Instance.ONLINE);
        instanceStorage.put(jobInstance);
        return true;
    }

    /** 注销执行器实例（从存储中移除） */
    @Override
    public void unregister(JobInstance jobInstance) {
        instanceStorage.remove(jobInstance);
    }

    /** 批量注册执行器实例，逐条复用单条注册逻辑 */
    @Override
    public void batchRegister(List<JobInstance> jobInstances) {
        if (jobInstances == null || jobInstances.isEmpty()) {
            return;
        }
        jobInstances.forEach(this::register);
    }
}
