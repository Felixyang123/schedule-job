package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.service.ScheduleJobService;
import com.wly.job.server.utils.CronUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ScheduleRunRecoveryTest {

    @BeforeAll
    static void initTableInfo() {
        // 单测环境无 MyBatis-Plus 自动装配，需手动初始化实体 lambda 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.wly.job.server.dao.entity.ScheduleRec.class);
    }

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleRecRep recRep = mock(ScheduleRecRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);

    private ScheduleRunRecovery recovery() {
        ScheduleProps props = new ScheduleProps();
        props.setReqTimeout(30000);
        return new ScheduleRunRecovery(jobRep, recRep, scheduleJobService, tracker, props, leaderElector);
    }

    private Job singleRunJob(long id) {
        return Job.builder().id(id).name("once-" + id).type(1).finished(0).cron("0 0 2 * * ?").build();
    }

    // ---- 接管恢复（recover）----

    @Test
    void staleRunningRecordsMarkedFail() {
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of());

        recovery().recover();

        verify(recRep).update(isNull(), any());
    }

    @Test
    void missedSingleRunFiredImmediately() {
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(null);

        recovery().recover();

        verify(scheduleJobService).schedule(any(Job.class));
        assertTrue(tracker.contains(1L));
    }

    @Test
    void alreadyDispatchedOccurrenceNotRepeated() {
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", LocalDateTime.now());
        Date dispatchedAt = Date.from(previous.atZone(CronUtils.zone()).toInstant());
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(
                ScheduleRec.builder().jobId(1L).scheduleTime(dispatchedAt).build());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void failedOccurrenceBeforeLastCronPointIsCaughtUp() {
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", LocalDateTime.now());
        Date oldFailure = Date.from(previous.minusDays(1).atZone(CronUtils.zone()).toInstant());
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(
                ScheduleRec.builder().jobId(1L).scheduleTime(oldFailure).status(-1).build());

        recovery().recover();

        verify(scheduleJobService).schedule(any(Job.class));
    }

    @Test
    void normalJobNeverCaughtUp() {
        Job normal = Job.builder().id(2L).name("every").type(0).finished(0).cron("0 0 2 * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(normal), List.of());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void finishedSingleRunNeverCaughtUp() {
        Job finished = Job.builder().id(3L).name("done").type(1).finished(1).cron("0 0 2 * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(finished), List.of());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
    }

    // ---- 常驻清扫（sweep）----

    @Test
    void sweepSkipsWhenNotLeader() {
        when(leaderElector.isLeader()).thenReturn(false);

        recovery().sweep();

        verify(recRep, never()).list(any(Wrapper.class));
        verify(recRep, never()).update(any(), any());
    }

    @Test
    void sweepReleasesStaleInFlightAndMarksFail() {
        when(leaderElector.isLeader()).thenReturn(true);
        tracker.add(1L);
        ScheduleRec stale = ScheduleRec.builder().jobId(1L)
                .scheduleTime(new Date(System.currentTimeMillis() - 60_000)).build();
        when(recRep.list(any(Wrapper.class))).thenReturn(List.of(stale), List.of());

        recovery().sweep();

        assertFalse(tracker.contains(1L));
        verify(recRep).update(isNull(), any());
    }

    @Test
    void sweepKeepsInFlightWhenFreshRecordExists() {
        when(leaderElector.isLeader()).thenReturn(true);
        tracker.add(1L);
        ScheduleRec stale = ScheduleRec.builder().jobId(1L)
                .scheduleTime(new Date(System.currentTimeMillis() - 60_000)).build();
        ScheduleRec fresh = ScheduleRec.builder().jobId(1L).build();
        when(recRep.list(any(Wrapper.class))).thenReturn(List.of(stale), List.of(fresh));

        recovery().sweep();

        assertTrue(tracker.contains(1L));
    }
}
