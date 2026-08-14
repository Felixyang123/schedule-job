package com.wly.job.server.schedule;

import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * schedule_rec 保留策略清理器（Spec §2.10 / 验收标准 10）。
 *
 * <p>每日一次删除超过保留期（{@code schedule.rec-retention-days}，默认 7 天）的**终态记录**：
 * {@code complete_time < now - retention} 且 {@code status IN (FAIL, SUCCESS)}。
 *
 * <p><strong>RUNNING（status=0）永不删除</strong>：RUNNING 是唯一非终态，{@link ScheduleRunRecovery}
 * 依赖它判定在途/陈旧，误删会破坏 At-Least-Once 语义。删除条件天然排除 status=0；
 * 对 complete_time 为 NULL 的行，SQL 的 {@code NULL < cutoff} 结果为 NULL（非真），同样不会删除。
 *
 * <p>生命周期遵循 AGENTS.md §5.2：daemon 线程 + running 标记 + 优雅停机
 * （awaitTermination 2s 后 {@code shutdownNow()}）。
 *
 * <p>说明：清理为幂等 DELETE，不要求主节点专属，故不注入 {@code ScheduleLeaderElector} 做 isLeader 门控。
 * 多 Admin 并发时可能重复扫描、等待相同行锁，或各自删除不同批次，但不会破坏调度语义；
 * 复合索引、单轮批次上限及批间停顿已限制数据库压力。保持各节点独立清理还可避免主备切换后
 * 新 Leader 等待接近一个完整清理周期才执行的问题。
 */
@Slf4j
@Component
public class ScheduleRecCleaner implements SmartLifecycle {

    /** 每日清理的固定延迟（小时） */
    private static final long SWEEP_INTERVAL_HOURS = 24L;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    /** 每批删除行数：限制单事务规模，同时保持每日清理吞吐 */
    static final int DELETE_BATCH_SIZE = 1000;

    /** 单轮清扫的最大批数：上限 20 万行，剩余留给下一周期，避免长期未清理的库把 DB 打满 */
    static final int MAX_BATCHES_PER_SWEEP = 200;

    /** 批间默认停顿：让出 DB 资源给在线调度写入，避免持续在同一表上高频加锁 */
    static final long DEFAULT_BATCH_PAUSE_MS = 50L;

    /** 启动后首次清扫的默认延迟：不阻塞 Spring 启动路径 */
    static final long DEFAULT_INITIAL_SWEEP_DELAY_MINUTES = 1L;

    /** 清扫线程池名：线程工厂与停机日志共用，避免两处字符串漂移 */
    private static final String POOL_NAME = "schedule-rec-cleaner";

    private final ScheduleRecRep recRep;

    private final ScheduleProps props;

    private final long batchPauseMs;

    private final long initialSweepDelayMinutes;

    @Autowired
    public ScheduleRecCleaner(ScheduleRecRep recRep, ScheduleProps props) {
        this(recRep, props, DEFAULT_BATCH_PAUSE_MS, DEFAULT_INITIAL_SWEEP_DELAY_MINUTES);
    }

    ScheduleRecCleaner(ScheduleRecRep recRep, ScheduleProps props, long batchPauseMs,
                       long initialSweepDelayMinutes) {
        this.recRep = recRep;
        this.props = props;
        this.batchPauseMs = batchPauseMs;
        this.initialSweepDelayMinutes = initialSweepDelayMinutes;
    }

    private volatile boolean running = false;

    private ScheduledExecutorService sweepExecutor;

    /**
     * 每日清扫：删除 complete_time 早于保留期截止点且处于终态（FAIL/SUCCESS）的记录。
     *
     * <p>包私有供单测直接驱动（同 {@link ScheduleRunRecovery#sweep()}），避免依赖调度线程时序。
     */
    void sweep() {
        try {
            doSweep();
        } catch (Exception e) {
            // 后台周期任务不得因单次 DB 异常中断（scheduleWithFixedDelay 遇异常会永久停止后续执行）
            log.warn("schedule_rec cleanup fail, will retry next daily cycle.", e);
        }
    }

    /**
     * 分批清扫过期终态记录：每批最多 {@link #DELETE_BATCH_SIZE} 行，避免单条大 DELETE
     * 长时间持锁或产生大事务。配合 schema 中 {@code idx_status_complete_time_id}
     * 复合索引，候选行查询无需全表扫描。
     *
     * <p>单轮批数封顶 {@link #MAX_BATCHES_PER_SWEEP}、批间停顿 {@link #DEFAULT_BATCH_PAUSE_MS}ms：
     * 长期未清理的库（百万级积压）不会在一轮里持续压满 DB，未清完的部分留给下一周期。
     * 被中断时复位中断标志并提前结束本轮。
     */
    private void doSweep() {
        int retentionDays = props.getRecRetentionDays();
        Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
        int totalRemoved = 0;
        int batches = 0;
        int removed;
        do {
            removed = recRep.getBaseMapper().deleteExpiredTerminalBatch(cutoff, DELETE_BATCH_SIZE);
            totalRemoved += removed;
            batches++;
            if (removed < DELETE_BATCH_SIZE || batches >= MAX_BATCHES_PER_SWEEP) {
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(batchPauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        log.info("ScheduleRec cleaner sweep done, removed={}, batches={}, cutoff={}, retentionDays={}, exhausted={}",
                totalRemoved, batches, cutoff, retentionDays, removed < DELETE_BATCH_SIZE);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sweepExecutor = MdcExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, POOL_NAME);
            thread.setDaemon(true);
            return thread;
        }));
        // 首次清扫延迟 1min 执行（消除重启前已超期的存量记录），不占用 Spring 启动路径：
        // 分批删除总时长长于单条大 DELETE，同步执行会把启动阻塞在 DB 上。
        // 清理使用“终态 + 截止时间”的幂等 DELETE；多 Admin 并发可能重复扫描、锁等待或分摊批次，
        // 但不读取/修改调度队列或 in-flight 状态，因此不影响调度语义，无需选主门控。
        sweepExecutor.scheduleWithFixedDelay(this::sweep, initialSweepDelayMinutes,
                TimeUnit.HOURS.toMinutes(SWEEP_INTERVAL_HOURS), TimeUnit.MINUTES);
        log.info("ScheduleRecCleaner started, first sweep in {}min, then every {}h",
                initialSweepDelayMinutes, SWEEP_INTERVAL_HOURS);
    }

    @Override
    public void stop() {
        running = false;
        ThreadPoolUtils.shutdownGracefully(sweepExecutor, POOL_NAME, GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        log.info("ScheduleRecCleaner stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
