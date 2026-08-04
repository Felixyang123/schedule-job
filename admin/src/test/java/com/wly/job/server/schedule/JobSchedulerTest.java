package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);
    private final ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);

    private JobScheduler scheduler() {
        when(leaderElector.isLeader()).thenReturn(true);
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery);
    }

    private ScheduleProps props() {
        ScheduleProps props = new ScheduleProps();
        props.setDispatchThreads(1);
        return props;
    }

    private static Job job(long id, String name, int type, int finished) {
        return Job.builder().id(id).name(name).type(type).finished(finished).cron("0/5 * * * * ?").build();
    }

    @Test
    void finishedSingleRunNeverReturnedByQueryAndSweepsInFlight() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(List.of(), List.of());
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());

        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }

    @Test
    void inFlightSingleRunIsNotRequeuedByFullReconcile() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(3L, "once", 1, 0))), List.of());
        JobScheduler scheduler = scheduler();
        tracker.add(3L);

        scheduler.reconcileQueuedJobs();

        verify(engine, never()).add(any());
    }

    @Test
    void workerIndexRoutesByJobId() {
        assertEquals(0, JobScheduler.workerIndex(2L, 2));
        assertEquals(1, JobScheduler.workerIndex(3L, 2));
        assertEquals(1, JobScheduler.workerIndex(-1L, 2));
        assertEquals(0, JobScheduler.workerIndex(null, 2));
    }

    @Test
    void maybeReconcileSkipsWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.maybeReconcile();

        verify(jobRep, never()).batchQueryJobViewsByCursor(anyLong(), anyInt());
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.handle(ScheduleJob.of(JobView.of(job(1L, "once", 0, 0))));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void loseLeadershipClearsTrackerAndStopsEngine() {
        tracker.add(1L);

        scheduler().onLoseLeadership();

        verify(engine).stop();
        assertFalse(tracker.contains(1L));
    }

    @Test
    void becomeLeaderStartsEngineAndReconciles() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(List.of());

        scheduler().onBecomeLeader();

        verify(engine).start();
    }
}
