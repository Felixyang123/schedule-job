package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.LeadershipListener;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobScheduler implements SmartLifecycle, LeadershipListener {

    private static final long BUILD_SCAN_INTERVAL_MS = 1000L;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps scheduleProps;

    private final ScheduleLeaderElector leaderElector;

    private final ScheduleRunRecovery scheduleRunRecovery;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private ExecutorService buildScheduleJobsExecutor;

    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;

        asyncBuildScheduleJobs();

        asyncScheduleJobs();
        log.info("JobScheduler started successfully.");
    }

    /**
     *
     * FIXME 1. 定时扫描数据库会有延迟，对于精度较高的任务可能无法准确执行，考虑替代方案。
     *       2. 数据全量加载到内存中容易OOM，大数据量需要考虑更稳定的方案。
     */
    private void asyncBuildScheduleJobs() {
        buildScheduleJobsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "job-scheduler-build");
            thread.setDaemon(true);
            return thread;
        });
        buildScheduleJobsExecutor.execute(() -> {
            while (running) {
                try {
                    long start = System.currentTimeMillis();
                    maybeReconcile();
                    long sleepTime = BUILD_SCAN_INTERVAL_MS - (System.currentTimeMillis() - start);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("job scheduler build error: ", e);
                }
            }
        });
    }

    /**
     * 增量对账：只入队新出现的启用任务，同时把已禁用/删除的任务从引擎移除，
     * 避免旧实现对同一任务每秒重复入队导致的重复调度。
     */
    void reconcileQueuedJobs() {
        Set<Long> seen = new HashSet<>();
        long offset = 0;
        var jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (Job job : jobs) {
                if (isFinishedSingleRun(job)) {
                    // 终态任务不进 seen：触发队列移除与 in-flight 清扫
                    continue;
                }
                seen.add(job.getId());
                if (singleRunTracker.contains(job.getId())) {
                    // 已出队等待回调，不能再次入队（At-Least-Once 契约，见 ADR-0003）
                    continue;
                }
                queuedJobs.compute(job.getId(), (id, queued) -> {
                    if (queued == null) {
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    if (isMetadataChanged(queued.job(), job)) {
                        // cron/参数/策略等发生变化时，替换队列条目，保证秒级生效
                        schedulerEngine.remove(queued);
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    return queued;
                });
            }
            offset = jobs.getLast().getId();
            jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        }

        // 清扫：已从 DB 消失（禁用/删除/Finished）的 in-flight 标记
        singleRunTracker.snapshot().forEach(id -> {
            if (!seen.contains(id)) {
                singleRunTracker.remove(id);
            }
        });

        queuedJobs.keySet().removeIf(id -> {
            if (seen.contains(id)) {
                return false;
            }
            ScheduleJob removed = queuedJobs.get(id);
            if (removed != null) {
                schedulerEngine.remove(removed);
            }
            return true;
        });
    }

    private void asyncScheduleJobs() {
        int threads = Math.max(1, scheduleProps.getDispatchThreads());
        dispatchExecutor = Executors.newSingleThreadExecutor(r -> namedThread("job-scheduler-dispatch", r));
        scheduleWorkers = new ExecutorService[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            scheduleWorkers[i] = Executors.newSingleThreadExecutor(
                    r -> namedThread("job-scheduler-worker-" + index, r));
        }
        dispatchExecutor.execute(() -> {
            while (running || !schedulerEngine.isEmpty()) {
                try {
                    ScheduleJob scheduleJob = schedulerEngine.take();
                    scheduleWorkers[workerIndex(scheduleJob.job().getId(), threads)]
                            .execute(() -> handle(scheduleJob));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    static int workerIndex(Long jobId, int threads) {
        return Math.floorMod(jobId == null ? 0 : jobId, threads);
    }

    private static Thread namedThread(String name, Runnable r) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    void handle(ScheduleJob scheduleJob) {
        try {
            if (!leaderElector.isLeader()) {
                // 已失去调度权：丢弃本次触发，由新主重新对账（ADR-0004 决策 #6）
                return;
            }
            Job job = scheduleJob.job();
            queuedJobs.remove(job.getId(), scheduleJob);
            if (isSingleRun(job)) {
                // 单次任务出队后不再自动回队，由执行回调决定 Finished/重试
                singleRunTracker.add(job.getId());
            } else {
                requeue(job);
            }

            scheduleJobService.schedule(job);
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
        }
    }

    private void requeue(Job job) {
        queuedJobs.compute(job.getId(), (id, old) -> {
            if (old != null) {
                // 构建线程可能已刷新过该条目，避免重复入队
                return old;
            }
            ScheduleJob next = ScheduleJob.of(job);
            schedulerEngine.add(next);
            return next;
        });
    }

    void maybeReconcile() {
        if (leaderElector.isLeader()) {
            reconcileQueuedJobs();
        }
    }

    @Override
    public void onBecomeLeader() {
        if (scheduleProps.isHaEnabled()) {
            scheduleRunRecovery.recover();
        }
        schedulerEngine.start();
        reconcileQueuedJobs();
        log.info("JobScheduler became leader, queue rebuilt");
    }

    @Override
    public void onLoseLeadership() {
        queuedJobs.forEach((jobId, queued) -> schedulerEngine.remove(queued));
        queuedJobs.clear();
        singleRunTracker.clear();
        schedulerEngine.stop();
        log.info("JobScheduler lost leadership, local queue cleared");
    }

    private boolean isMetadataChanged(Job queued, Job current) {
        return !Objects.equals(queued.getCron(), current.getCron())
                || !Objects.equals(queued.getExecuteParam(), current.getExecuteParam())
                || !Objects.equals(queued.getStrategy(), current.getStrategy())
                || !Objects.equals(queued.getType(), current.getType());
    }

    private boolean isSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode();
    }

    private boolean isFinishedSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode()
                && Objects.equals(job.getFinished(), 1);
    }

    @Override
    public void stop() {
        this.running = false;
        schedulerEngine.stop();
        shutdownGracefully(buildScheduleJobsExecutor);
        shutdownGracefully(dispatchExecutor);
        if (scheduleWorkers != null) {
            for (ExecutorService worker : scheduleWorkers) {
                shutdownGracefully(worker);
            }
        }
        log.info("JobScheduler stopped.");
    }

    private void shutdownGracefully(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
