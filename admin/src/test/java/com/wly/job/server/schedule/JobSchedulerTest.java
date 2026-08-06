package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JobScheduler} 派发执行与生命周期测试；对账逻辑见 {@link QueueReconcilerTest}。
 */
class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);
    private final ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);

    private JobScheduler scheduler() {
        when(leaderElector.isLeader()).thenReturn(true);
        when(changeRep.listAfter(anyLong(), anyInt())).thenReturn(List.of());
        when(jobRep.batchQueryJobViewsByCursor(anyLong(), anyInt())).thenReturn(List.of());
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery, changeRep,
                new MetricsRegistry(new SimpleMeterRegistry()));
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

    @Test
    void handleSyncFailureReleasesInFlightAndRecordsRequeue() {
        doThrow(new RuntimeException("no instance")).when(scheduleJobService).schedule(any());
        JobScheduler scheduler = scheduler();

        scheduler.handle(ScheduleJob.of(JobView.of(job(7L, "once", 1, 0))));

        assertFalse(tracker.contains(7L));
        verify(changeRep).record(7L, JobChangeTypeEnum.REQUEUE.getCode(), "system", null, "once");
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.handle(ScheduleJob.of(JobView.of(job(1L, "once", 0, 0))));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void workerIndexRoutesByJobId() {
        assertEquals(0, JobScheduler.workerIndex(2L, 2));
        assertEquals(1, JobScheduler.workerIndex(3L, 2));
        assertEquals(1, JobScheduler.workerIndex(-1L, 2));
        assertEquals(0, JobScheduler.workerIndex(null, 2));
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
    void phaseStopsSchedulerFirst() {
        // Spec §2.8：JobScheduler 停机 phase 最高（先停派发/对账），早于 ScheduleRecQueue(0)
        assertEquals(Integer.MAX_VALUE - 10, scheduler().getPhase());
    }
}
