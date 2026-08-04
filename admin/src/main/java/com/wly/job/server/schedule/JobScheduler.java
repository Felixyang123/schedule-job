package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
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

    private static final int CHANGE_FEED_BATCH_SIZE = 500;

    /** 每 60 次扫描（约 60s）执行一次全量兜底对账 */
    private static final int FULL_RECONCILE_EVERY_SCANS = 60;

    /** 每 300 次扫描（约 5min）清理已消费变更记录 */
    private static final int CHANGE_CLEANUP_EVERY_SCANS = 300;

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps scheduleProps;

    private final ScheduleLeaderElector leaderElector;

    private final ScheduleRunRecovery scheduleRunRecovery;

    private final JobChangeRep changeRep;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private long changeFeedWatermark = 0L;

    private int scansSinceFullReconcile = 0;

    private int scansSinceChangeCleanup = 0;

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
     * 调度队列构建与对账（方案见 docs/spec/2026-08-04-scheduler-scalability-spec.md）：
     * 稳态每秒消费变更源，成本 ∝ 变更量；每 60s 执行一次投影全量兜底对账，自愈直改库与漏写。
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
        if (!leaderElector.isLeader()) {
            return;
        }
        consumeChangeFeed();
        if (++scansSinceFullReconcile >= FULL_RECONCILE_EVERY_SCANS) {
            scansSinceFullReconcile = 0;
            reconcileQueuedJobs();
        }
        if (++scansSinceChangeCleanup >= CHANGE_CLEANUP_EVERY_SCANS) {
            scansSinceChangeCleanup = 0;
            changeRep.deleteUpTo(changeFeedWatermark);
        }
    }

    void consumeChangeFeed() {
        for (JobChange change : changeRep.listAfter(changeFeedWatermark, CHANGE_FEED_BATCH_SIZE)) {
            applyChange(change.getJobId());
            changeFeedWatermark = change.getId();
        }
    }

    void applyChange(Long jobId) {
        Job current = jobRep.getById(jobId);
        queuedJobs.compute(jobId, (id, queued) -> {
            if (singleRunTracker.contains(id)) {
                // 在途：队列必无该任务，跳过整条记录（防跨主迟到 REQUEUE 双发）
                return queued;
            }
            if (!isActive(current)) {
                if (queued != null) {
                    schedulerEngine.remove(queued);
                }
                return null;
            }
            JobView view = JobView.of(current);
            if (queued == null) {
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                return scheduleJob;
            }
            if (isMetadataChanged(queued.job(), view)) {
                schedulerEngine.remove(queued);
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                return scheduleJob;
            }
            return queued;
        });
    }

    private boolean isActive(Job job) {
        return job != null
                && Objects.equals(job.getStatus(), Job.ENABLE)
                && !(Objects.equals(job.getType(), JobTypeEnum.SINGLE.getCode())
                        && Objects.equals(job.getFinished(), 1));
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
        JobView job = scheduleJob.job();
        try {
            if (!leaderElector.isLeader()) {
                return;
            }
            if (isSingleRun(job)) {
                // 先置 in-flight 再摘除条目，闭合消费线程插入的竞态窗口
                singleRunTracker.add(job.id());
                queuedJobs.remove(job.id(), scheduleJob);
            } else {
                queuedJobs.remove(job.id(), scheduleJob);
                requeue(job);
            }
            scheduleJobService.schedule(job.toJob());
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
            if (isSingleRun(job)) {
                singleRunTracker.remove(job.id());
                changeRep.record(job.id(), JobChangeTypeEnum.REQUEUE.getCode(),
                        "system", null, job.name());
            }
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
        schedulerEngine.clear();
        schedulerEngine.start();
        reconcileQueuedJobs();
        changeFeedWatermark = changeRep.maxId();
        log.info("JobScheduler became leader, queue rebuilt");
    }

    @Override
    public void onLoseLeadership() {
        queuedJobs.clear();
        singleRunTracker.clear();
        schedulerEngine.clear();
        schedulerEngine.stop();
        changeFeedWatermark = 0L;
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
