package com.wly.job.server.convert;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.utils.CronUtils;

public class JobBeanConverter {

    public static Job convert(JobInfo jobInfo) {
        Job job = Job.builder()
                .groupName(jobInfo.getGroup())
                .name(jobInfo.getJobname())
                .description(jobInfo.getDescription())
                .executeParam(jobInfo.getExecuteParam())
                .cron(jobInfo.getCron())
                .status(Job.ENABLE)
                .type(jobInfo.getType())
                .strategy(jobInfo.getStrategy())
                // 计算下次运行时间
                .nextRunTime(CronUtils.getNextExecutionSecond(jobInfo.getCron()))
                .build();
        return job.init();
    }

    public static JobInstance convert(Instance instance) {
        return JobInstance.builder()
                .discoveryName(instance.getJobname())
                .host(instance.getHost())
                .port(instance.getPort())
                .status(instance.getStatus())
                .expireTime(instance.getExpireTime())
                .build();
    }

    public static Instance convert(JobInstance jobInstance) {
        return Instance.builder()
                .jobname(jobInstance.getDiscoveryName())
                .host(jobInstance.getHost())
                .port(jobInstance.getPort())
                .status(jobInstance.getStatus())
                .expireTime(jobInstance.getExpireTime())
                .build();
    }
}
