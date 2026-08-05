package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);
    private final ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);

    private JobScheduler scheduler() {
        return scheduler(new MetricsRegistry(new SimpleMeterRegistry()));
    }

    private JobScheduler scheduler(MetricsRegistry metrics) {
        when(leaderElector.isLeader()).thenReturn(true);
        when(changeRep.listAfter(anyLong(), anyInt())).thenReturn(List.of());
        when(jobRep.batchQueryJobViewsByCursor(anyLong(), anyInt())).thenReturn(List.of());
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery, changeRep,
                metrics);
    }

    private ScheduleProps props() {
        ScheduleProps props = new ScheduleProps();
        props.setDispatchThreads(1);
        return props;
    }

    private static Job job(long id, String name, int type, int finished) {
        return Job.builder().id(id).name(name).type(type).finished(finished)
                .status(1).cron("0/5 * * * * ?").build();
    }

    private static JobChange change(long id, long jobId) {
        JobChange c = new JobChange();
        c.setId(id);
        c.setJobId(jobId);
        return c;
    }

    @Test
    void finishedSingleRunNeverReturnedByQueryAndSweepsInFlight() {
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        JobScheduler scheduler = scheduler();
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());

        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }

    @Test
    void inFlightSingleRunIsNotRequeuedByFullReconcile() {
        JobScheduler scheduler = scheduler();
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(3L, "once", 1, 0))), List.of());
        tracker.add(3L);

        scheduler.reconcileQueuedJobs();

        verify(engine, never()).add(any());
    }

    @Test
    void consumeChangeFeedAddsNewJobAndAdvancesWatermark() {
        JobScheduler scheduler = scheduler();
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        scheduler.consumeChangeFeed();
        scheduler.consumeChangeFeed();

        verify(engine).add(any());
        verify(changeRep).listAfter(11L, 500);
    }

    @Test
    void applyChangeSkipsAddWhileInFlight() {
        JobScheduler scheduler = scheduler();
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 0));
        tracker.add(2L);

        scheduler.consumeChangeFeed();

        verify(engine, never()).add(any());
    }

    @Test
    void applyChangeRemovesQueuedEntryWhenFinished() {
        JobScheduler scheduler = scheduler();
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "once", 1, 0))), List.of());
        scheduler.reconcileQueuedJobs();
        verify(engine).add(any());

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 1));

        scheduler.consumeChangeFeed();

        verify(engine).remove(any());
    }

    @Test
    void lateRequeueWhileAlreadyQueuedDoesNotDuplicate() {
        JobScheduler scheduler = scheduler();
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());
        scheduler.reconcileQueuedJobs();

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        scheduler.consumeChangeFeed();

        verify(engine).add(any());
        verify(engine, never()).remove(any());
    }

    @Test
    void handleSyncFailureReleasesInFlightAndRecordsRequeue() {
        doThrow(new RuntimeException("no instance")).when(scheduleJobService).schedule(any());
        JobScheduler scheduler = scheduler();

        scheduler.handle(ScheduleJob.of(JobView.of(job(7L, "once", 1, 0))));

        assertFalse(tracker.contains(7L));
        verify(changeRep).record(7L, JobChangeTypeEnum.REQUEUE.getCode(), "system", null, "once");
    }

    @Test
    void fullReconcileRunsEvery60Scans() {
        JobScheduler scheduler = scheduler();
        for (int i = 0; i < 60; i++) {
            scheduler.maybeReconcile();
        }
        verify(jobRep, times(1)).batchQueryJobViewsByCursor(anyLong(), anyInt());
    }

    @Test
    void cleanupRunsEvery300Scans() {
        JobScheduler scheduler = scheduler();
        for (int i = 0; i < 300; i++) {
            scheduler.maybeReconcile();
        }
        verify(changeRep).deleteUpTo(anyLong());
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
        verify(changeRep, never()).listAfter(anyLong(), anyInt());
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.handle(ScheduleJob.of(JobView.of(job(1L, "once", 0, 0))));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void loseLeadershipClearsTrackerAndEngine() {
        tracker.add(1L);

        scheduler().onLoseLeadership();

        verify(engine).clear();
        verify(engine).stop();
        assertFalse(tracker.contains(1L));
    }

    @Test
    void becomeLeaderStartsEngineReconcilesAndSetsWatermark() {
        when(changeRep.maxId()).thenReturn(42L);

        scheduler().onBecomeLeader();

        verify(engine).start();
        verify(changeRep).maxId();
    }

    @Test
    void metricsGaugesReflectQueueBacklogAndChangeLag() {
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());
        JobScheduler scheduler = scheduler(metrics);
        scheduler.registerMetrics();

        // 变更源滞后：maxId(10) - 已消费水印(3) = 7；队列积压：未入队任何任务 = 0
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(3L, 2L)));
        when(changeRep.maxId()).thenReturn(10L);
        scheduler.consumeChangeFeed();

        Gauge backlog = metrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, () -> 0.0);
        Gauge lag = metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0);
        assertEquals(0.0, backlog.value(), 0.001);
        assertEquals(7.0, lag.value(), 0.001);
    }

    @Test
    void loseLeadershipResetsChangeLagGauge() {
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());
        JobScheduler scheduler = scheduler(metrics);
        scheduler.registerMetrics();
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(3L, 2L)));
        when(changeRep.maxId()).thenReturn(10L);
        scheduler.consumeChangeFeed();
        assertEquals(7.0, metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0).value(), 0.001);

        scheduler.onLoseLeadership();

        assertEquals(0.0, metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0).value(), 0.001);
    }

    @Test
    void phaseStopsSchedulerFirst() {
        // Spec §2.8：JobScheduler 停机 phase 最高（先停派发/对账），早于 ScheduleRecQueue(0)
        assertEquals(Integer.MAX_VALUE - 10, scheduler().getPhase());
    }
}
