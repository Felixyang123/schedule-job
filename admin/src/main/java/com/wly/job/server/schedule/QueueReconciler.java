package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 调度队列对账器（JobScheduler 拆分出的"构建/对账"组件，仅主节点运行）。
 *
 * <p>职责（ADR-0005 变更源增量对账 + 投影全量兜底）：
 * <ul>
 *   <li><b>job-scheduler-build 对账线程</b>：每秒消费变更源（Change Feed）做增量对账，
 *       成本 ∝ 变更量；每 60 次扫描执行一次投影全量兜底对账（自愈直改库与漏写变更记录）；
 *       每 300 次扫描清理已消费变更记录。</li>
 *   <li>消费端无视 change_type，只以 jobId 回查当前行 diff，天然幂等；单条记录异常被隔离，
 *       不阻断整批消费（坏 cron / 脏数据由全量对账兜底暴露）。</li>
 * </ul>
 * <p>与 {@link JobScheduler}（派发执行）共享 {@code queuedJobs} / {@link SchedulerEngine} /
 * {@link SingleRunTracker} 三个可变状态，通过 {@code running} 供应商感知停止信号。
 */
@Slf4j
class QueueReconciler {

    static final long BUILD_SCAN_INTERVAL_MS = 1000L;

    static final int CHANGE_FEED_BATCH_SIZE = 500;

    /** 每 60 次扫描执行一次全量兜底对账（正常负载下约 60s；DB 变慢时随固定延迟周期顺延） */
    static final int FULL_RECONCILE_EVERY_SCANS = 60;

    /** 每 300 次扫描清理已消费变更记录（正常负载下约 5min；DB 变慢时随固定延迟周期顺延） */
    static final int CHANGE_CLEANUP_EVERY_SCANS = 300;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    /** 对账线程池名：线程工厂与停机日志共用，避免两处字符串漂移 */
    private static final String POOL_NAME = "job-scheduler-build";

    private final JobRep jobRep;

    private final JobChangeRep changeRep;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleLeaderElector leaderElector;

    private final MetricsRegistry metrics;

    /** 已投递到调度引擎的任务（jobId -> 队列中的实例），与派发侧共享 */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs;

    /** 运行信号（来自 JobScheduler 的 running 标志） */
    private final BooleanSupplier running;

    private ScheduledExecutorService buildScheduleJobsExecutor;

    private long changeFeedWatermark = 0L;

    /**
     * 变更源滞后（maxId - 已消费水印）的缓存值：在 consumeChangeFeed 每轮扫描后更新，
     * Gauge 抓取时只读缓存，避免抓取路径打 DB。
     */
    private volatile long changeLag = 0L;

    private int scansSinceFullReconcile = 0;

    private int scansSinceChangeCleanup = 0;

    QueueReconciler(JobRep jobRep, JobChangeRep changeRep, SchedulerEngine schedulerEngine,
                    SingleRunTracker singleRunTracker, ScheduleLeaderElector leaderElector,
                    MetricsRegistry metrics, ConcurrentMap<Long, ScheduleJob> queuedJobs,
                    BooleanSupplier running) {
        this.jobRep = jobRep;
        this.changeRep = changeRep;
        this.schedulerEngine = schedulerEngine;
        this.singleRunTracker = singleRunTracker;
        this.leaderElector = leaderElector;
        this.metrics = metrics;
        this.queuedJobs = queuedJobs;
        this.running = running;
    }

    /** 启动对账线程（仅主节点执行有效逻辑） */
    void start() {
        buildScheduleJobsExecutor = MdcExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, POOL_NAME);
            thread.setDaemon(true);
            return thread;
        }));
        // 固定延迟从上一次执行结束后计时：DB 变慢时自动降低后台扫描频率，不追赶补跑，
        // 避免 scheduleAtFixedRate 在长耗时后连续触发、进一步放大数据库压力。
        buildScheduleJobsExecutor.scheduleWithFixedDelay(this::reconcileSafely,
                0L, BUILD_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 隔离单轮异常：ScheduledExecutorService 的周期任务若异常外泄，会永久取消后续执行。 */
    private void reconcileSafely() {
        if (!running.getAsBoolean()) {
            return;
        }
        try {
            maybeReconcile();
        } catch (Exception e) {
            log.error("job scheduler build error: ", e);
        }
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
     * 滞后计算（Gauge 用）按批次大小短路：批次不满说明已追平到当前最大 id，免去每轮一次
     * {@code maxId()} 查询（稳态 job_change 无新增时该查询是纯浪费）；仅当批次拉满
     * （可能还有存量）才查一次 maxId 计算滞后。滞后为近似值，接受"刚好有新记录插入但未消费"
     * 瞬间显示 0 的偏差。
     */
    void consumeChangeFeed() {
        List<JobChange> changes = changeRep.listAfter(changeFeedWatermark, CHANGE_FEED_BATCH_SIZE);
        for (JobChange change : changes) {
            try {
                applyChange(change.getJobId());
            } catch (Exception e) {
                // 单条隔离：坏 cron / 脏数据不得阻断整批变更源消费（水印继续推进，坏作业由全量对账兜底暴露）
                log.error("consumeChangeFeed applyChange failed, jobId: {}, skip this record", change.getJobId(), e);
            }
            changeFeedWatermark = change.getId();
        }
        changeLag = changes.size() == CHANGE_FEED_BATCH_SIZE
                ? Math.max(0, changeRep.maxId() - changeFeedWatermark)
                : 0L;
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
                try {
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
                } catch (Exception e) {
                    // 单条隔离：坏 cron / 脏数据不得中断全量对账，错误作业保留原队列条目并跳过本轮
                    log.error("reconcileQueuedJobs compute failed, jobId: {}, skip this record", job.id(), e);
                }
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

    private boolean isMetadataChanged(JobView queued, JobView current) {
        return !Objects.equals(queued.cron(), current.cron())
                || !Objects.equals(queued.executeParam(), current.executeParam())
                || !Objects.equals(queued.strategy(), current.strategy())
                || !Objects.equals(queued.type(), current.type());
    }

    /** 成为主节点后：水印拨到当前最大 id，跳过存量历史记录（仅消费接管后的新变更） */
    void skipToLatestWatermark() {
        changeFeedWatermark = changeRep.maxId();
    }

    /** 失去主节点后：水印与扫描计数归零；滞后归零避免 Gauge 报告误导性历史积压 */
    void resetState() {
        changeFeedWatermark = 0L;
        changeLag = 0L;
        scansSinceFullReconcile = 0;
        scansSinceChangeCleanup = 0;
    }

    /** 优雅停止：置 running 已由 JobScheduler 控制，此处仅关闭对账线程池 */
    void stop() {
        ThreadPoolUtils.shutdownGracefully(buildScheduleJobsExecutor, POOL_NAME,
                GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
    }
}
