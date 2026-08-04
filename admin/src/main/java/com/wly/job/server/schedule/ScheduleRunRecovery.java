package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.service.ScheduleJobService;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在途记录恢复（ADR-0004 决策 #8/#9/#14）：
 * <p>
 * 常驻清扫（sweep，主节点每 ha-stale-sweep-seconds 执行一次）：
 * 1. releaseStaleInFlight——存在陈旧 RUNNING 且无更新在途记录的单次任务，移除 in-flight，
 *    使"按 Cron 自然重试"恢复（仅置 FAIL 不够：in-flight 仍会挡住 reconcile 重新入队）；
 * 2. markStaleRunningFailed——把超过 reqTimeout+5s 的 RUNNING 记录统一置 FAIL（记录卫生）。
 * <p>
 * 接管恢复（recover，成为主时立即执行）：
 * 1. 陈旧 RUNNING 全量置 FAIL（覆盖重启/丢回调场景）；
 * 2. 对未派发过的单次任务补触发一次错过的火点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRunRecovery implements SmartLifecycle {

    private static final long STALE_RUNNING_GRACE_MS = 5000L;

    private final JobRep jobRep;

    private final ScheduleRecRep recRep;

    private final ScheduleJobService scheduleJobService;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps props;

    private final ScheduleLeaderElector leaderElector;

    private final JobChangeRep changeRep;

    private volatile boolean running = false;

    private ScheduledExecutorService sweepExecutor;

    /**
     * 接管恢复：成为主时立即调用。
     */
    public void recover() {
        markStaleRunningFailed();
        catchUpMissedSingleRuns();
    }

    /**
     * 常驻清扫：仅主节点执行。
     */
    void sweep() {
        if (!leaderElector.isLeader()) {
            return;
        }
        releaseStaleInFlight();
        markStaleRunningFailed();
    }

    void releaseStaleInFlight() {
        long cutoff = staleCutoffMillis();
        List<ScheduleRec> staleRecs = recRep.list(Wrappers.<ScheduleRec>lambdaQuery()
                .select(ScheduleRec::getJobId, ScheduleRec::getScheduleTime)
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .lt(ScheduleRec::getScheduleTime, new Date(cutoff)));
        List<ScheduleRec> freshRecs = recRep.list(Wrappers.<ScheduleRec>lambdaQuery()
                .select(ScheduleRec::getJobId)
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .ge(ScheduleRec::getScheduleTime, new Date(cutoff)));
        Set<Long> freshJobIds = freshRecs.stream().map(ScheduleRec::getJobId).collect(Collectors.toSet());
        staleRecs.stream().map(ScheduleRec::getJobId).distinct()
                .filter(jobId -> !freshJobIds.contains(jobId))
                .forEach(jobId -> {
                    singleRunTracker.remove(jobId);
                    changeRep.record(jobId, JobChangeTypeEnum.REQUEUE.getCode(), "system", null, null);
                    log.warn("Stale in-flight released, jobId: {}", jobId);
                });
    }

    void markStaleRunningFailed() {
        long cutoff = staleCutoffMillis();
        recRep.update(null, Wrappers.<ScheduleRec>lambdaUpdate()
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .lt(ScheduleRec::getScheduleTime, new Date(cutoff))
                .set(ScheduleRec::getStatus, ScheduleRec.FAIL)
                .set(ScheduleRec::getCompleteTime, new Date())
                .set(ScheduleRec::getExecuteResult, "stale running after timeout sweep"));
    }

    private long staleCutoffMillis() {
        return System.currentTimeMillis() - props.getReqTimeout() - STALE_RUNNING_GRACE_MS;
    }

    void catchUpMissedSingleRuns() {
        long offset = 0;
        var jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (Job job : jobs) {
                if (!isUnfinishedSingleRun(job)) {
                    continue;
                }
                LocalDateTime previous = CronUtils.getPreviousExecution(job.getCron(), LocalDateTime.now());
                if (previous == null) {
                    continue;
                }
                Instant previousInstant = previous.atZone(CronUtils.zone()).toInstant();
                ScheduleRec latest = recRep.getOne(Wrappers.<ScheduleRec>lambdaQuery()
                        .eq(ScheduleRec::getJobId, job.getId())
                        .orderByDesc(ScheduleRec::getScheduleTime)
                        .last("LIMIT 1"));
                if (latest != null && !latest.getScheduleTime().toInstant().isBefore(previousInstant)) {
                    // 该触发点已派发过（RUNNING/FAIL/SUCCESS 均视为已尝试），不重复补
                    continue;
                }
                dispatchCatchUp(job);
            }
            offset = jobs.getLast().getId();
            jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        }
    }

    private void dispatchCatchUp(Job job) {
        singleRunTracker.add(job.getId());
        try {
            scheduleJobService.schedule(job);
        } catch (Exception e) {
            log.error("single-run catch-up dispatch fail, job: {}", job.getName(), e);
            singleRunTracker.remove(job.getId());
        }
    }

    private boolean isUnfinishedSingleRun(Job job) {
        return job.getType() != null
                && job.getType() == JobTypeEnum.SINGLE.getCode()
                && !Objects.equals(job.getFinished(), 1);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "schedule-stale-run-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1, props.getHaStaleSweepSeconds());
        sweepExecutor.scheduleWithFixedDelay(this::sweep, interval, interval, TimeUnit.SECONDS);
        log.info("ScheduleRunRecovery started, sweep interval: {}s", interval);
    }

    @Override
    public void stop() {
        running = false;
        if (sweepExecutor != null) {
            sweepExecutor.shutdown();
            try {
                if (!sweepExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    sweepExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sweepExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
