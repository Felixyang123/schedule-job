package com.wly.job.samples.center.registry;

import com.wly.config.core.bean.pojo.req.OpenInstanceRegisterReq;
import com.wly.config.core.client.RegistryHelper;
import com.wly.config.core.config.RegistryClientProps;
import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public record RegisterCenterInstanceRegistry(DefaultRemoteJobRegistry defaultRemoteJobRegistry,
                                             RegistryHelper registryHelper,
                                             RegistryClientProps props) implements RemoteJobRegistry {
    @Override
    public void register(JobInfo jobInfo, String instanceKey) {
        defaultRemoteJobRegistry.register(jobInfo, instanceKey);
    }

    @Override
    public void register(JobInstance jobInstance) {
        OpenInstanceRegisterReq req = new OpenInstanceRegisterReq();
        req.setEnv(this.props.getEnv());
        req.setAccessToken(this.props.getAccessToken());
        req.setSrcApp(this.props.getAppname());
        req.setExt(this.props.getExt());

        req.setAppname(jobInstance.getDiscoveryKey());
        req.setIp(jobInstance.getHost());
        req.setPort(String.valueOf(jobInstance.getPort()));
        try {
            registryHelper.register(req);
        } catch (RuntimeException e) {
            log.warn("Register-center instance registration failed, discoveryKey: {}",
                    jobInstance.getDiscoveryKey(), e);
        }
    }
}
