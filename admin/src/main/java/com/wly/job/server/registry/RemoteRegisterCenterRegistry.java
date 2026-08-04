package com.wly.job.server.registry;

import com.wly.config.core.bean.pojo.InstanceDTO;
import com.wly.config.core.bean.pojo.req.OpenInstanceRegisterReq;
import com.wly.config.core.client.RegistryClient;
import com.wly.config.core.client.RegistryHelper;
import com.wly.config.core.config.RegistryClientProps;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Instance;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 远程注册中心式执行器实例注册中心（record 形式）。
 *
 * <p>接入外部注册中心（{@code wly-config} 的 RegistryClient / RegistryHelper）作为实例后端：
 * <ul>
 *   <li>注册：将执行器实例的 IP / 端口、应用名、环境等信息上报外部注册中心，实现跨机房的
 *       实例共享与高可用发现。</li>
 *   <li>发现：从外部注册中心按作业发现键拉取实例列表并转换为 {@link JobInstance}（恒为在线）。</li>
 *   <li>注销：外部注册中心未提供下线能力，实例依赖心跳过期自动失效。</li>
 * </ul>
 */
@Slf4j
public record RemoteRegisterCenterRegistry(RegistryHelper registryHelper,
                                           RegistryClient registryClient,
                                           RegistryClientProps registryClientProps,
                                           ScheduleProps scheduleProps) implements Registry {
    /** 从外部注册中心发现可用执行器实例（转换为 JobInstance，发现即视为在线） */
    @Override
    public List<JobInstance> discover(String name) {
        List<InstanceDTO> instanceDTOS = registryHelper.get(name);
        return instanceDTOS.stream().map(instanceDTO -> JobInstance.builder()
                .discoveryKey(name)
                .host(instanceDTO.getIp())
                .port(Integer.valueOf(instanceDTO.getPort()))
                .status(Instance.ONLINE)
                .build()).toList();
    }

    /** 注册 / 刷新执行器实例：组装环境、应用名、IP 端口后调用注册中心接口；受实例注册开关控制 */
    @Override
    public boolean register(JobInstance jobInstance) {
        if (!Boolean.TRUE.equals(scheduleProps.getEnableRegisterInstance())) {
            return false;
        }

        log.debug("Register job instance: {}", jobInstance);
        OpenInstanceRegisterReq req = new OpenInstanceRegisterReq();
        req.setEnv(registryClientProps.getEnv());
        req.setAccessToken(registryClientProps.getAccessToken());
        req.setAppname(jobInstance.getDiscoveryKey());
        req.setIp(jobInstance.getHost());
        req.setPort(String.valueOf(jobInstance.getPort()));
        req.setSrcApp(registryClientProps.getAppname());
        registryClient.register(registryClientProps.parseServerAddress(), req);
        return true;
    }

    /** 注销执行器实例（当前为空实现，见类注释） */
    @Override
    public void unregister(JobInstance jobInstance) {
        // 注册中心（RegistryClient）未提供下线能力，保持空实现；实例依赖心跳过期自动失效
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
