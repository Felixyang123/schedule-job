package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleJobServiceTest {

    private final Registry registry = mock(Registry.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final ScheduleJobService service =
            new ScheduleJobService(registry, jobRep, recQueue, scheduleService, changeRep);

    private static JobInfo info() {
        return JobInfo.builder()
                .jobname("j").group("g").cron("0/5 * * * * ?")
                .instance(JobInstance.builder().build())
                .build();
    }

    @Test
    void registerJobRecordsRegisterChange() {
        when(jobRep.save(any(Job.class))).thenReturn(true);

        service.registerJob(info());

        verify(changeRep).record(any(), eq(JobChangeTypeEnum.REGISTER.getCode()),
                eq("system"), isNull(), eq("j"));
    }

    @Test
    void duplicateRegisterDoesNotRecordChange() {
        when(jobRep.save(any(Job.class))).thenThrow(new DuplicateKeyException("dup"));

        service.registerJob(info());

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }
}
