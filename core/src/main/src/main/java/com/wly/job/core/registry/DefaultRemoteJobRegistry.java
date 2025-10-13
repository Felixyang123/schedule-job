package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.core.helper.RestClientHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;

@Slf4j
public record DefaultRemoteJobRegistry(RestClientHelper restClientHelper) implements RemoteJobRegistry {

    @Override
    public void register(JobInfo jobInfo) {
        try {
            log.debug("Register job info: {}", jobInfo);
            Result<Void> result = restClientHelper.post("/open/job/register", jobInfo, new ParameterizedTypeReference<>() {
            });
            if (!result.getSuccess()) {
                log.error("Register job info fail: {}", result.getMessage());
            }
        } catch (Exception e) {
            log.error("Register job info error: ", e);
        }
    }

    @Override
    public void register(JobInstance jobInstance) {
        try {
            log.debug("Register job instance: {}", jobInstance);
            Result<Void> result = restClientHelper.post("/open/job/instance/register", jobInstance, new ParameterizedTypeReference<>() {
            });
            if (!result.getSuccess()) {
                log.error("Register job instance fail: {}", result.getMessage());
            }
        } catch (Exception e) {
            log.error("Register job instance error: ", e);
        }
    }
}
