package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.LeadershipListener;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Admin 调度发动机（SmartLifecycle + LeadershipListener）：负责调度队列的构建/对账、到期任务派发与执行。
 * <p>
 * 线程模型（三个环节、三类线程）：
 * <ul>
 *   <li><b>job-scheduler-build（对账线程）</b>：仅主节点运行。每秒消费变更源（Change Feed）做增量对账
 *       （成本 ∝ 变更量，见 ADR-0005）；每 60 次扫描执行一次投影全量兜底对账（自愈直改库与漏写变更记录）；
 *       每 300 次扫描清理已消费变更记录。</li>
 *   <li><b>job-scheduler-dispatch（派发线程）</b>：从调度引擎 {@code take()} 取到期任务，按 jobId 分片
 *       （workerIndex = jobId % threads）投递给 worker 线程；派发前检查 {@code isLeader}（1s 刷新，
 *       双发窗口 ≤1s 属已接受语义，见 ADR-0004）。</li>
 *   <li><b>job-scheduler-worker-N（worker 池）</b>：{@code dispatch-threads} 个，逐个执行 {@link #handle}——
 *       普通任务执行完成后按 Cron 计算下次时间回队；单次任务先置 in-flight 再摘除队列条目，失败/超时由
 *       回调或常驻清扫释放 in-flight 并写"失败重试"变更记录重新入队（At-Least-Once）。</li>
 * </ul>
 * <p>
 * 内存模型：调度引擎与 {@code queuedJobs} 仅持有轻量投影 {@link JobView}
 * （id/name/cron/executeParam/strategy/type），避免在任务规模增大时把完整 {@link Job} 实体加载进内存；
 * 元数据变化（cron/param/strategy/type）通过 diff 幂等重建队列条目。
 * <p>
 * 主备切换：成为主节点（{@link #onBecomeLeader}）时先执行 ScheduleRunRecovery（清扫陈旧 RUNNING、
 * 补触发单次任务错过的火点），再清空并启动调度引擎、全量对账、变更源水印拨到当前最大 id；
 * 失去主节点（{@link #onLoseLeadership}）时清空本地队列与 in-flight 标记、停止引擎，暂停全部调度逻辑，
 * Standby 节点仅保留注册与管理接口。
 */
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

    /** 可观测性指标封装（Spec §2.7，仅只读观察不改调度语义） */
    private final MetricsRegistry metrics;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private long changeFeedWatermark = 0L;

    /**
     * 变更源滞后（maxId - 已消费水印）的缓存值：在 consumeChangeFeed 每轮扫描后更新，
     * Gauge 抓取时只读缓存，避免抓取路径打 DB。
     */
    private volatile long changeLag = 0L;

    private int scansSinceFullReconcile = 0;

    private int scansSinceChangeCleanup = 0;

    private ExecutorService buildScheduleJobsExecutor;

    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    /**
     * 启动调度：开启对账线程（build）与派发线程（dispatch + worker 池）。
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;

        registerMetrics();

        asyncBuildScheduleJobs();

        asyncScheduleJobs();
        log.info("JobScheduler started successfully.");
    }

    /**
     * 调度队列构建与对账（方案见 docs/spec/2026-08-04-scheduler-scalability-spec.md）：
     * 稳态每秒消费变更源，成本 ∝ 变更量；每 60s 执行一次投影全量兜底对账，自愈直改库与漏写。
     */
    private void asyncBuildScheduleJobs() {
        buildScheduleJobsExecutor = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "job-scheduler-build");
            thread.setDaemon(true);
            return thread;
        }));
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
     * 对账线程单次扫描（仅主节点执行）：
     * 1. 消费变更源做增量对账；
     * 2. 每 60 次扫描触发一次投影全量兜底对账（自愈直改库与漏写变更记录导致的队列漂移）；
     * 3. 每 300 次扫描清理已消费变更记录，控制 job_change 表增长。
     */
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

    /**
     * 注册可观测性 Gauge（Spec §2.7）：队列积压数 + 变更源滞后。
     * start() 由 running 标志守卫只执行一次，Gauge 值在 Prometheus 抓取时实时求值。
     */
    void registerMetrics() {
        metrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, () -> (double) queuedJobs.size());
        metrics.gauge(MetricsRegistry.JOB_CHANGE_LAG, () -> (double) changeLag);
    }

    /**
     * 消费变更源：按 id 水印增量拉取（LIMIT 500），逐条回查当前行做幂等 applyChange。
     * 消费端无视 change_type，只以 jobId 回查当前行 diff，天然幂等（见 ADR-0005）。
     * 每轮扫描后缓存变更源滞后（maxId - 水印），供 Gauge 抓取（避免抓取路径打 DB）。
     */
    void consumeChangeFeed() {
        for (JobChange change : changeRep.listAfter(changeFeedWatermark, CHANGE_FEED_BATCH_SIZE)) {
            applyChange(change.getJobId());
            changeFeedWatermark = change.getId();
        }
        changeLag = Math.max(0, changeRep.maxId() - changeFeedWatermark);
    }

    /**
     * 应用单条变更：回查作业当前行，与 queuedJobs 现有条目做幂等 diff。
     * 在途单次任务直接跳过（防止跨主迟到的"失败重试"变更与当前派发造成并发双发）；
     * 任务不存在/禁用/已 Finished 则移除队列条目；元数据变化则移除旧条目并入队新条目；无变化则保留。
     */
    void applyChange(Long jobId) {
        Job current = jobRep.getById(jobId);
        queuedJobs.compute(jobId, (id, queued) -> {
            if (singleRunTracker.contains(id)) {
                // 在途：队列必无该任务，跳过整条记录（防跨主迟到 REQUEUE 双发）
                log.debug("applyChange skip, jobId: {}, reason: in-flight", id);
                return queued;
            }
            if (!isActive(current)) {
                if (queued != null) {
                    schedulerEngine.remove(queued);
                }
                log.debug("applyChange remove, jobId: {}", id);
                return null;
            }
            JobView view = JobView.of(current);
            if (queued == null) {
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                log.debug("applyChange add, jobId: {}", id);
                return scheduleJob;
            }
            if (isMetadataChanged(queued.job(), view)) {
                schedulerEngine.remove(queued);
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                log.debug("applyChange replace, jobId: {}", id);
                return scheduleJob;
            }
            log.debug("applyChange skip, jobId: {}, reason: no change", id);
            return queued;
        });
    }

    /** 任务可入队判断：状态为启用，且单次任务未置 Finished（Finished 是单次任务唯一业务终态，finished=1 ⇒ type=1） */
    private boolean isActive(Job job) {
        return job != null
                && Objects.equals(job.getStatus(), Job.ENABLE)
                && !(Objects.equals(job.getType(), JobTypeEnum.SINGLE.getCode())
                        && Objects.equals(job.getFinished(), 1));
    }

    /**
     * 投影全量兜底对账（游标分页，批大小 1000）：批量回查 status=1 且 finished=0 的作业视图，
     * 只入队新增/元数据变化的任务；查询未覆盖的任务（已禁用/删除/已完成）统一移除队列条目，
     * 并同步清扫其 in-flight 标记（该标记已无对应在途派发，若不清理将永久挡住后续入队）。
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
        dispatchExecutor = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(r -> namedThread("job-scheduler-dispatch", r)));
        scheduleWorkers = new ExecutorService[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            scheduleWorkers[i] = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(
                    r -> namedThread("job-scheduler-worker-" + index, r)));
        }
        dispatchExecutor.execute(() -> {
            // 停止时继续耗尽引擎中剩余到期任务，避免丢火点；引擎为空且 running=false 时退出
            while (running || !schedulerEngine.isEmpty()) {
                try {
                    ScheduleJob scheduleJob = schedulerEngine.take();
                    // requestId 在派发线程（任务入口）注入：cron 场景 MDC 为空则生成；worker 提交由
                    // MdcExecutorService 自动透传快照，handle 内与下游 schedule() 直接读取（Spec 2026-08-06 §2.3）
                    MDC.put("requestId", UUID.randomUUID().toString().replace("-", ""));
                    try {
                        // 按 jobId 分片：同一作业始终落在同一 worker 线程，串行执行避免并发乱序
                        scheduleWorkers[workerIndex(scheduleJob.job().id(), threads)]
                                .execute(() -> handle(scheduleJob));
                    } finally {
                        MDC.remove("requestId");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** 按 jobId 分片到 worker 线程索引（null 兜底取 0） */
    static int workerIndex(Long jobId, int threads) {
        return Math.floorMod(jobId == null ? 0 : jobId, threads);
    }

    private static Thread namedThread(String name, Runnable r) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 执行到期任务（worker 线程池中运行）：
     * 普通任务先摘除队列条目，执行完成后按 Cron 计算下次时间回队（{@link #requeue}）；
     * 单次任务先置 in-flight 再摘除队列条目——若先摘除，对账/消费线程可能在窗口内重新入队造成双发；
     * 执行异常时单次任务释放 in-flight 并写"失败重试"变更记录，由变更源 ≤1s 内重新入队。
     * 执行前再检查一次 isLeader（内存标志，双发窗口 ≤1s），防止失去主权后仍继续派发。
     */
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
            log.debug("job dispatched, jobId: {}, name: {}", job.id(), job.name());
        } catch (Exception e) {
            // requestId 已由派发线程注入并经 MdcExecutorService 透传，此处日志直接携带（Spec 2026-08-06 §2.3）
            log.error("schedule job execute error: jobId={}, name={}", job.id(), job.name(), e);
            if (isSingleRun(job)) {
                singleRunTracker.remove(job.id());
                changeRep.record(job.id(), JobChangeTypeEnum.REQUEUE.getCode(),
                        "system", null, job.name());
            }
        }
    }

    /**
     * 普通任务执行完成后重新入队：以 queuedJobs.compute 幂等回队，
     * 若对账线程已抢先入队新条目则复用，避免同一任务重复入队。
     */
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

    /**
     * 成为主节点（Leader）回调：
     * 先执行 ScheduleRunRecovery.recover()（HA 开启时）——把陈旧 RUNNING 全量置 FAIL、
     * 补触发单次任务错过的火点；再清空并启动调度引擎、全量对账重建队列；
     * 最后把变更源水印拨到当前最大 id，跳过存量历史记录（仅消费接管后的新变更）。
     */
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

    /**
     * 失去主节点（转为 Standby）回调：清空本地队列、in-flight 标记与调度引擎并停表，
     * 变更源水印归零；此后对账与派发全部暂停，仅保留注册与管理接口，等待下次夺回主节点时重建。
     */
    @Override
    public void onLoseLeadership() {
        queuedJobs.clear();
        singleRunTracker.clear();
        schedulerEngine.clear();
        schedulerEngine.stop();
        changeFeedWatermark = 0L;
        // Standby 节点不消费变更源，滞后归零避免 Gauge 报告误导性历史积压
        changeLag = 0L;
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

    /**
     * 优雅停机：置 running=false 让各循环线程自行退出，先停调度引擎（阻塞 take 立即唤醒），
     * 再按序关闭 build/dispatch/worker 线程池（有界等待后 shutdownNow）。
     */
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

    /**
     * 停机顺序编排（Spec §2.8）：返回最高 phase，保证正常停机时最先停止
     * （停派发/对账），早于 ScheduleRecQueue(0)、NettyLifecycle(MIN_VALUE+10)
     * 与 ScheduleLeaderElector(MIN_VALUE)——先停调度，再排空落库，再关连接与回调。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 10;
    }
}
