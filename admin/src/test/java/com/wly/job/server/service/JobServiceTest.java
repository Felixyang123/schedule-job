package com.wly.job.server.service;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.pojo.req.EditJobReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final JobService jobService = new JobService(jobRep, scheduleJobService, changeRep);

    private static Job existing(long id, int type) {
        return Job.builder().id(id).name("j").type(type).finished(0).status(1).build();
    }

    @Test
    void editWithInvalidCronRejected() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).cron("bad-cron").build();

        assertThrows(ScheduleException.class, () -> jobService.edit(req));
    }

    @Test
    void editWithValidCronUpdatesAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).cron("0/5 * * * * ?").build();

        assertDoesNotThrow(() -> jobService.edit(req));

        verify(jobRep).updateById(any(Job.class));
        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.EDIT.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void editWithoutCronSkipsValidation() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).description("only desc").build();

        assertDoesNotThrow(() -> jobService.edit(req));

        verify(jobRep).updateById(any(Job.class));
    }

    @Test
    void editConvertingSingleRunToGeneralResetsFinished() {
        when(jobRep.getById(1L)).thenReturn(
                Job.builder().id(1L).name("j").type(1).finished(1).status(1).build());

        jobService.edit(EditJobReq.builder().id(1L).type(0).build());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRep).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFinished());
    }

    @Test
    void editNotFoundThrows() {
        when(jobRep.getById(1L)).thenReturn(null);

        assertThrows(ScheduleException.class, () -> jobService.edit(EditJobReq.builder().id(1L).build()));
    }

    @Test
    void switchStatusFlipsAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));

        jobService.switchStatus(1L);

        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.SWITCH.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void deleteRemovesAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));

        jobService.delete(1L);

        verify(jobRep).removeById(1L);
        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.DELETE.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void deleteNotFoundThrows() {
        when(jobRep.getById(1L)).thenReturn(null);

        assertThrows(ScheduleException.class, () -> jobService.delete(1L));
    }
}
