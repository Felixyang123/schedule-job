package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.client.registry.LocalCacheJobInstanceRegistry;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleJobService {
    private final LocalCacheJobInstanceRegistry registry;

    private final JobRep jobRep;

    public void registerJob(JobInfo jobInfo) {
        if (registry.register(jobInfo.getInstance())) {
            Job job = JobBeanConverter.convert(jobInfo).init();
            job.setCreator("system");
            job.setUpdater("system");
            try {
                jobRep.save(job);
            } catch (DuplicateKeyException exception) {
                log.warn("job already exists, register fail, job: {}", job.getGroupName() + ":" + job.getName());
            }
        }
    }

    public void registerInstance(JobInstance instance) {
        registry.register(instance);
    }
}
