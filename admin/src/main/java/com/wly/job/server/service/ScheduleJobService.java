package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public record ScheduleJobService(Registry registry,
                                 JobRep jobRep,
                                 ScheduleRecRep recRep,
                                 ScheduleService scheduleService) {

    public void registerJob(JobInfo jobInfo) {
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
        recRep.save(scheduleRec);
        try {
            scheduleService.schedule(requestId, job);
        } catch (Exception e) {
            ScheduleRec updateRec = ScheduleRec.builder().id(scheduleRec.getId()).completeTime(new Date())
                    .status(ScheduleRec.FAIL).executeResult(e.getMessage()).build();
            recRep.updateById(updateRec);
            throw e;
        }
    }
}
