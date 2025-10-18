package com.wly.job.server.convert;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.enumeration.JobStatusEnum;
import com.wly.job.server.enumeration.ScheduleJobStatusEnum;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.resp.GroupResp;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.pojo.resp.ScheduleRecResp;

import java.util.Date;

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
                .build();
        return job.init();
    }

    public static JobInstance convert(Instance instance) {
        return JobInstance.builder()
                .discoveryKey(instance.getName())
                .host(instance.getHost())
                .port(instance.getPort())
                .status(instance.getStatus())
                .expireTime(instance.getExpireTime())
                .build();
    }

    public static Instance convert(JobInstance jobInstance) {
        return Instance.builder()
                .name(jobInstance.getDiscoveryKey())
                .host(jobInstance.getHost())
                .port(jobInstance.getPort())
                .status(jobInstance.getStatus())
                .expireTime(jobInstance.getExpireTime())
                .build();
    }

    public static GroupResp convert(JobGroup jobGroup) {
        return GroupResp.builder()
                .id(jobGroup.getId())
                .name(jobGroup.getName())
                .description(jobGroup.getDescription())
                .createTime(jobGroup.getCreateTime())
                .creator(jobGroup.getCreator())
                .build();
    }

    public static JobGroup convert(AddGroupReq req) {
        return JobGroup.builder()
                .name(req.getName())
                .description(req.getDescription())
                .createTime(new Date())
                .creator("")
                .build();
    }

    public static JobResp convert(Job job) {
        return JobResp.builder()
                .id(job.getId())
                .groupName(job.getGroupName())
                .name(job.getName())
                .description(job.getDescription())
                .executeParam(job.getExecuteParam())
                .cron(job.getCron())
                .status(job.getStatus())
                .statusDesc(JobStatusEnum.getDescription(job.getStatus()))
                .type(job.getType())
                .typeDesc(JobTypeEnum.getDescription(job.getType()))
                .strategy(job.getStrategy())
                .strategyDesc(ScheduleStrategyEnum.getDescription(job.getStrategy()))
                .build();
    }

    public static Job convert(EditJobReq req) {
        return Job.builder()
                .id(req.getId())
                .description(req.getDescription())
                .executeParam(req.getExecuteParam())
                .cron(req.getCron())
                .status(req.getStatus())
                .type(req.getType())
                .strategy(req.getStrategy())
                .build();
    }

    public static ScheduleRecResp convert(ScheduleRec scheduleRec) {
        return ScheduleRecResp.builder()
                .id(scheduleRec.getId())
                .jobId(scheduleRec.getJobId())
                .requestId(scheduleRec.getRequestId())
                .executeParam(scheduleRec.getExecuteParam())
                .executeResult(scheduleRec.getExecuteResult())
                .status(scheduleRec.getStatus())
                .statusDesc(ScheduleJobStatusEnum.getDescription(scheduleRec.getStatus()))
                .scheduleTime(scheduleRec.getScheduleTime())
                .completeTime(scheduleRec.getCompleteTime())
                .operator(scheduleRec.getOperator())
                .build();
    }
}
