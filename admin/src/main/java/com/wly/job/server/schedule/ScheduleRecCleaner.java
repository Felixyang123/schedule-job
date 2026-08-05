package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>说明：清理为幂等 DELETE，不要求主节点专属，故不注入 {@code ScheduleLeaderElector} 做 isLeader 门控；
 * 多 Admin 并发执行同一删除不会产生任何调度语义影响（第二个节点删除 0 行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRecCleaner implements SmartLifecycle {

    /** 每日清理的固定延迟与初始延迟（小时） */
    private static final long SWEEP_INTERVAL_HOURS = 24L;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    private final ScheduleRecRep recRep;

    private final ScheduleProps props;

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

    private void doSweep() {
        int retentionDays = props.getRecRetentionDays();
        Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
        int removed = recRep.getBaseMapper().delete(Wrappers.<ScheduleRec>lambdaQuery()
                .in(ScheduleRec::getStatus, ScheduleRec.FAIL, ScheduleRec.SUCCESS)
                .lt(ScheduleRec::getCompleteTime, cutoff));
        log.info("ScheduleRec cleaner sweep done, removed={}, cutoff={}, retentionDays={}",
                removed, cutoff, retentionDays);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "schedule-rec-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        sweepExecutor.scheduleWithFixedDelay(this::sweep,
                SWEEP_INTERVAL_HOURS, SWEEP_INTERVAL_HOURS, TimeUnit.HOURS);
        log.info("ScheduleRecCleaner started, sweep every {}h (initial delay {}h)",
                SWEEP_INTERVAL_HOURS, SWEEP_INTERVAL_HOURS);
    }

    @Override
    public void stop() {
        running = false;
        if (sweepExecutor != null) {
            sweepExecutor.shutdown();
            try {
                if (!sweepExecutor.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    sweepExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sweepExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("ScheduleRecCleaner stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
