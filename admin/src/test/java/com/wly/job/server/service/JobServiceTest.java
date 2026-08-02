package com.wly.job.server.service;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.pojo.req.EditJobReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobServiceTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final JobService jobService = new JobService(jobRep, scheduleJobService);

    @Test
    void editWithInvalidCronRejected() {
        EditJobReq req = EditJobReq.builder().id(1L).cron("bad-cron").build();
        assertThrows(ScheduleException.class, () -> jobService.edit(req));
    }

    @Test
    void editWithValidCronUpdates() {
        EditJobReq req = EditJobReq.builder().id(1L).cron("0/5 * * * * ?").build();
        assertDoesNotThrow(() -> jobService.edit(req));
        verify(jobRep).updateById(any(Job.class));
    }

    @Test
    void editWithoutCronSkipsValidation() {
        EditJobReq req = EditJobReq.builder().id(1L).description("only desc").build();
        assertDoesNotThrow(() -> jobService.edit(req));
        verify(jobRep).updateById(any(Job.class));
    }
}
