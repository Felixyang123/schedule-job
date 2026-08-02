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

@Slf4j
public record RemoteRegisterCenterRegistry(RegistryHelper registryHelper,
                                           RegistryClient registryClient,
                                           RegistryClientProps registryClientProps,
                                           ScheduleProps scheduleProps) implements Registry {
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

    @Override
    public void unregister(JobInstance jobInstance) {
        // 注册中心（RegistryClient）未提供下线能力，保持空实现；实例依赖心跳过期自动失效
    }

    @Override
    public void batchRegister(List<JobInstance> jobInstances) {
        if (jobInstances == null || jobInstances.isEmpty()) {
            return;
        }
        jobInstances.forEach(this::register);
    }
}
