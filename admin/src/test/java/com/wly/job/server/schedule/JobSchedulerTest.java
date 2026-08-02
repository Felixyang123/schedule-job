package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();

    private JobScheduler scheduler() {
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker);
    }

    @Test
    void finishedSingleRunJobIsNeverQueuedAndSweepsInFlight() {
        Job finished = Job.builder().id(1L).name("once").type(1).finished(1).cron("0/5 * * * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(finished), List.of());
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        org.junit.jupiter.api.Assertions.assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        Job job = Job.builder().id(2L).name("every").type(0).finished(0).cron("0/5 * * * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(job), List.of());

        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }
}
