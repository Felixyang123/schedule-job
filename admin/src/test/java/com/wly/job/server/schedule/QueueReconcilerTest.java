package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QueueReconciler} 单元测试：变更源增量对账、投影全量兜底、水印推进与指标注册。
 */
class QueueReconcilerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);

    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private QueueReconciler reconciler(SingleRunTracker tracker, MetricsRegistry metrics) {
        when(leaderElector.isLeader()).thenReturn(true);
        when(changeRep.listAfter(anyLong(), anyInt())).thenReturn(List.of());
        when(jobRep.batchQueryJobViewsByCursor(anyLong(), anyInt())).thenReturn(List.of());
        return new QueueReconciler(jobRep, changeRep, engine, tracker, leaderElector, metrics, queuedJobs, () -> true);
    }

    private QueueReconciler reconciler(SingleRunTracker tracker) {
        return reconciler(tracker, new MetricsRegistry(new SimpleMeterRegistry()));
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

    /** 生成拉满一批（CHANGE_FEED_BATCH_SIZE 条）的变更列表：批次拉满才会触发 maxId() 查询。 */
    private static List<JobChange> fullChangeBatch(long jobId) {
        return IntStream.rangeClosed(1, QueueReconciler.CHANGE_FEED_BATCH_SIZE)
                .mapToObj(i -> change(i, jobId))
                .toList();
    }

    @Test
    void finishedSingleRunNeverReturnedByQueryAndSweepsInFlight() {
        SingleRunTracker tracker = new SingleRunTracker();
        tracker.add(1L);

        reconciler(tracker).reconcileQueuedJobs();

        verify(engine, never()).add(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());

        reconciler.reconcileQueuedJobs();
        reconciler.reconcileQueuedJobs();

        verify(engine).add(any());
    }

    @Test
    void inFlightSingleRunIsNotRequeuedByFullReconcile() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(3L, "once", 1, 0))), List.of());
        tracker.add(3L);

        reconciler.reconcileQueuedJobs();

        verify(engine, never()).add(any());
    }

    @Test
    void consumeChangeFeedAddsNewJobAndAdvancesWatermark() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        reconciler.consumeChangeFeed();
        reconciler.consumeChangeFeed();

        verify(engine).add(any());
        verify(changeRep).listAfter(11L, 500);
    }

    @Test
    void applyChangeSkipsAddWhileInFlight() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 0));
        tracker.add(2L);

        reconciler.consumeChangeFeed();

        verify(engine, never()).add(any());
    }

    @Test
    void applyChangeRemovesQueuedEntryWhenFinished() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "once", 1, 0))), List.of());
        reconciler.reconcileQueuedJobs();
        verify(engine).add(any());

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 1));

        reconciler.consumeChangeFeed();

        verify(engine).remove(any());
    }

    @Test
    void lateRequeueWhileAlreadyQueuedDoesNotDuplicate() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());
        reconciler.reconcileQueuedJobs();

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        reconciler.consumeChangeFeed();

        verify(engine).add(any());
        verify(engine, never()).remove(any());
    }

    @Test
    void fullReconcileRunsEvery60Scans() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        for (int i = 0; i < 60; i++) {
            reconciler.maybeReconcile();
        }
        verify(jobRep, times(1)).batchQueryJobViewsByCursor(anyLong(), anyInt());
    }

    @Test
    void cleanupRunsEvery300Scans() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        for (int i = 0; i < 300; i++) {
            reconciler.maybeReconcile();
        }
        verify(changeRep).deleteUpTo(anyLong());
    }

    @Test
    void maybeReconcileSkipsWhenNotLeader() {
        SingleRunTracker tracker = new SingleRunTracker();
        QueueReconciler reconciler = reconciler(tracker);
        when(leaderElector.isLeader()).thenReturn(false);

        reconciler.maybeReconcile();

        verify(jobRep, never()).batchQueryJobViewsByCursor(anyLong(), anyInt());
        verify(changeRep, never()).listAfter(anyLong(), anyInt());
    }

    @Test
    void metricsGaugesReflectQueueBacklogAndChangeLag() {
        SingleRunTracker tracker = new SingleRunTracker();
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());
        QueueReconciler reconciler = reconciler(tracker, metrics);
        reconciler.registerMetrics();

        // 批次拉满（500 = LIMIT）才会查 maxId：maxId(510) - 已消费水印(500) = 10；
        // 队列积压：同一 jobId 幂等入队只入一次 = 1
        when(changeRep.listAfter(0L, 500)).thenReturn(fullChangeBatch(2L));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));
        when(changeRep.maxId()).thenReturn(510L);
        reconciler.consumeChangeFeed();

        Gauge backlog = metrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, () -> 0.0);
        Gauge lag = metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0);
        assertEquals(1.0, backlog.value(), 0.001);
        assertEquals(10.0, lag.value(), 0.001);
    }

    @Test
    void skipsMaxIdQueryWhenBatchNotFull() {
        SingleRunTracker tracker = new SingleRunTracker();
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());
        QueueReconciler reconciler = reconciler(tracker, metrics);
        reconciler.registerMetrics();
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(3L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        reconciler.consumeChangeFeed();

        verify(changeRep, never()).maxId();
        assertEquals(0.0, metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0).value(), 0.001);
    }

    @Test
    void resetStateResetsChangeLagAndWatermark() {
        SingleRunTracker tracker = new SingleRunTracker();
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());
        QueueReconciler reconciler = reconciler(tracker, metrics);
        reconciler.registerMetrics();
        when(changeRep.listAfter(0L, 500)).thenReturn(fullChangeBatch(2L));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));
        when(changeRep.maxId()).thenReturn(510L);
        reconciler.consumeChangeFeed();
        assertEquals(10.0, metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0).value(), 0.001);

        reconciler.resetState();

        assertEquals(0.0, metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> 0.0).value(), 0.001);
    }
}
