package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.JobView;
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
     * 已知限制（调度语义见 docs/spec/2026-08-03-admin-ha-spec.md 决策 #8）：
     * 1. 定时扫描数据库存在最长约 1s 的调度延迟，秒级精度任务可能错过火点：普通任务不补偿，单次任务仅在 HA 接管时补触发；
     * 2. 数据以游标分批加载进内存，任务量极大时存在内存压力，需评估更稳定的方案。
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

    void maybeReconcile() {
        if (leaderElector.isLeader()) {
            reconcileQueuedJobs();
        }
    }

    /**
     * 全量对账：投影游标查询（status=1 且 finished=0），只入队新增/变更任务，移除消失任务。
     */
    void reconcileQueuedJobs() {
        Set<Long> seen = new HashSet<>();
        long offset = 0;
        var jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (JobView job : jobs) {
                seen.add(job.id());
                if (singleRunTracker.contains(job.id())) {
                    continue;
                }
                queuedJobs.compute(job.id(), (id, queued) -> {
                    if (queued == null) {
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    if (isMetadataChanged(queued.job(), job)) {
                        schedulerEngine.remove(queued);
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    return queued;
                });
            }
            offset = jobs.getLast().id();
            jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        }

        // 清扫：已从查询消失（禁用/删除/Finished）的 in-flight 标记与队列条目
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
                    scheduleWorkers[workerIndex(scheduleJob.job().id(), threads)]
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
                return;
            }
            JobView job = scheduleJob.job();
            queuedJobs.remove(job.id(), scheduleJob);
            if (isSingleRun(job)) {
                singleRunTracker.add(job.id());
            } else {
                requeue(job);
            }
            scheduleJobService.schedule(job.toJob());
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
        }
    }

    private void requeue(JobView job) {
        queuedJobs.compute(job.id(), (id, old) -> {
            if (old != null) {
                return old;
            }
            ScheduleJob next = ScheduleJob.of(job);
            schedulerEngine.add(next);
            return next;
        });
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

    private boolean isMetadataChanged(JobView queued, JobView current) {
        return !Objects.equals(queued.cron(), current.cron())
                || !Objects.equals(queued.executeParam(), current.executeParam())
                || !Objects.equals(queued.strategy(), current.strategy())
                || !Objects.equals(queued.type(), current.type());
    }

    private boolean isSingleRun(JobView job) {
        return job.type() != null && job.type() == JobTypeEnum.SINGLE.getCode();
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
