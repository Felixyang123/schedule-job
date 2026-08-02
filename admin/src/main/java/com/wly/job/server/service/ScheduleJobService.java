package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import com.wly.job.server.utils.CronUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public record ScheduleJobService(Registry registry,
                                 JobRep jobRep,
                                 ScheduleRecQueue recQueue,
                                 ScheduleService scheduleService) {

    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null || jobInfo.getInstance() == null) {
            throw new ScheduleException("JobInfo and instance must not be null");
        }
        if (!StringUtils.hasText(jobInfo.getJobname()) || !StringUtils.hasText(jobInfo.getCron())) {
            throw new ScheduleException("Job name and cron must not be blank");
        }
        CronUtils.checkCronExpression(jobInfo.getCron());

        registry.register(jobInfo.getInstance());
        Job job = JobBeanConverter.convert(jobInfo).init();
        job.setCreator("system");
        job.setUpdater("system");
        try {
            jobRep.save(job);
        } catch (DuplicateKeyException exception) {
            log.warn("job already exists, register fail, job: {}", job.getGroupName() + ":" + job.getName());
        }
    }

    public void registerInstance(JobInstance instance) {
        registry.register(instance);
    }

    public void schedule(Job job) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ScheduleRec scheduleRec = ScheduleRec.builder()
                .jobId(job.getId())
                .requestId(requestId)
                .executeParam(job.getExecuteParam())
                .scheduleTime(new Date())
                .status(ScheduleRec.RUNNING)
                .operator(UserSessionContext.getUserName())
                .build();
        // 先入队 RUNNING 记录，再触发调度；失败/成功回写同样走队列，保证 FIFO 顺序
        recQueue.save(scheduleRec);
        try {
            scheduleService.schedule(requestId, job);
        } catch (Exception e) {
            recQueue.markFail(requestId, e.getMessage());
            throw e;
        }
    }
}
