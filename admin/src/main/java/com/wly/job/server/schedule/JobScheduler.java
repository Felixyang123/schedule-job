package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.LeadershipListener;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Admin 调度发动机（SmartLifecycle + LeadershipListener）：编排"队列对账"与"到期任务派发执行"。
 * <p>
 * 结构（自 JobScheduler 拆分，职责聚焦）：
 * <ul>
 *   <li><b>对账</b>：委托 {@link QueueReconciler}（job-scheduler-build 线程 + 变更源消费 + 全量兜底对账）。</li>
 *   <li><b>派发执行</b>：本类持有 job-scheduler-dispatch 线程与 job-scheduler-worker-N 池——
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
public class JobScheduler implements SmartLifecycle, LeadershipListener {

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    /** 派发线程池名：线程工厂与停机日志共用，避免两处字符串漂移 */
    private static final String DISPATCH_POOL_NAME = "job-scheduler-dispatch";

    /** 执行 worker 线程池名前缀（实际名为 前缀 + 分片下标） */
    private static final String WORKER_POOL_NAME_PREFIX = "job-scheduler-worker-";

    private final ScheduleJobService scheduleJobService;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps scheduleProps;

    private final ScheduleLeaderElector leaderElector;

    private final ScheduleRunRecovery scheduleRunRecovery;

    private final JobChangeRep changeRep;

    /** 队列对账组件（变更源消费 + 全量兜底对账 + 水印） */
    private final QueueReconciler reconciler;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），对账与派发共享，用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    public JobScheduler(JobRep jobRep, ScheduleJobService scheduleJobService, SchedulerEngine schedulerEngine,
                        SingleRunTracker singleRunTracker, ScheduleProps scheduleProps,
                        ScheduleLeaderElector leaderElector, ScheduleRunRecovery scheduleRunRecovery,
                        JobChangeRep changeRep, MetricsRegistry metrics) {
        this.scheduleJobService = scheduleJobService;
        this.schedulerEngine = schedulerEngine;
        this.singleRunTracker = singleRunTracker;
        this.scheduleProps = scheduleProps;
        this.leaderElector = leaderElector;
        this.scheduleRunRecovery = scheduleRunRecovery;
        this.changeRep = changeRep;
        this.reconciler = new QueueReconciler(jobRep, changeRep, schedulerEngine, singleRunTracker,
                leaderElector, metrics, queuedJobs, () -> running);
    }

    /**
     * 启动调度：开启对账线程（QueueReconciler）与派发线程（dispatch + worker 池）。
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;

        reconciler.registerMetrics();
        reconciler.start();
        asyncScheduleJobs();
        log.info("JobScheduler started successfully.");
    }

    /**
     * 到期任务派发（dispatch 线程 + worker 池）：
     * dispatch 线程从引擎 take 到期任务，按 jobId 分片投递给 worker；worker 逐个执行 {@link #handle}。
     */
    private void asyncScheduleJobs() {
        int threads = Math.max(1, scheduleProps.getDispatchThreads());
        dispatchExecutor = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(r -> namedThread(DISPATCH_POOL_NAME, r)));
        scheduleWorkers = new ExecutorService[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            scheduleWorkers[i] = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(
                    r -> namedThread(WORKER_POOL_NAME_PREFIX + index, r)));
        }
        dispatchExecutor.execute(() -> {
            // 停止时继续耗尽引擎中剩余到期任务，避免丢火点；引擎为空且 running=false 时退出
            while (running || !schedulerEngine.isEmpty()) {
                try {
                    ScheduleJob scheduleJob = schedulerEngine.take();
                    // 调度任务入口注入（Spec 2026-08-06 §2.3）：cron 无 HTTP 链路，traceId 与 requestId
                    // 同值（调度执行即链路起点）；worker 提交由 MdcExecutorService 自动透传快照，
                    // handle 内与下游 schedule() 直接读取
                    String r2 = UUID.randomUUID().toString().replace("-", "");
                    MDC.put("traceId", r2);
                    MDC.put("requestId", r2);
                    try {
                        // 按 jobId 分片：同一作业始终落在同一 worker 线程，串行执行避免并发乱序
                        scheduleWorkers[workerIndex(scheduleJob.job().id(), threads)]
                                .execute(() -> handle(scheduleJob));
                    } finally {
                        MDC.remove("traceId");
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

    private boolean isSingleRun(JobView job) {
        return job.type() != null && job.type() == JobTypeEnum.SINGLE.getCode();
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
        reconciler.reconcileQueuedJobs();
        reconciler.skipToLatestWatermark();
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
        reconciler.resetState();
        log.info("JobScheduler lost leadership, local queue cleared");
    }

    /**
     * 优雅停机：置 running=false 让各循环线程自行退出，先停调度引擎（阻塞 take 立即唤醒），
     * 再按序关闭对账/派发/worker 线程池（有界等待后 shutdownNow）。
     */
    @Override
    public void stop() {
        this.running = false;
        schedulerEngine.stop();
        reconciler.stop();
        ThreadPoolUtils.shutdownGracefully(dispatchExecutor, DISPATCH_POOL_NAME,
                GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        if (scheduleWorkers != null) {
            for (int i = 0; i < scheduleWorkers.length; i++) {
                ThreadPoolUtils.shutdownGracefully(scheduleWorkers[i], WORKER_POOL_NAME_PREFIX + i,
                        GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        }
        log.info("JobScheduler stopped.");
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
