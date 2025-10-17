package com.wly.job.samples.center.registry;

import com.wly.config.core.bean.pojo.req.OpenInstanceRegisterReq;
import com.wly.config.core.client.RegistryHelper;
import com.wly.config.core.config.RegistryClientProps;
import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.core.registry.DefaultRemoteJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import org.springframework.stereotype.Component;

@Component
public record RegisterCenterInstanceRegistry(DefaultRemoteJobRegistry defaultRemoteJobRegistry,
                                             RegistryHelper registryHelper,
                                             RegistryClientProps props) implements RemoteJobRegistry {
    @Override
    public void register(JobInfo jobInfo) {
        defaultRemoteJobRegistry.register(jobInfo);
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
        registryHelper.register(req);
    }
}
