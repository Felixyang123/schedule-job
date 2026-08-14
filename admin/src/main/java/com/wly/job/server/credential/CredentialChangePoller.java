package com.wly.job.server.credential;

import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.dao.entity.CredentialChange;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 凭证变更源轮询器（Spec 2026-08-14 §5，ADR-0006 决策 #26）：跨节点缓存失效广播。
 *
 * <p><b>重要：本组件有意不做 leader 门控。</b>{@code /open/**} 凭证校验在所有 Admin 节点执行
 * （standby 节点同样校验），各节点摘要缓存必须同步刷新——照抄 {@code QueueReconciler} /
 * {@code ScheduleRunRecovery} 的 {@code isLeader} 前置判断会造成 standby 节点缓存永不刷新，
 * 吊销/轮换在 standby 上不生效。水印为<b>进程内独立</b>：启动置 {@code max(id)} 不回放历史。
 *
 * <p>消费循环：1s 轮询 {@code id > watermark LIMIT 100}，逐条
 * {@link CredentialService#invalidate} 本节点缓存，单条失败隔离（WARN + 跳过）不阻断整批。
 *
 * <p><b>记录清理（纯 housekeeping，应当门控）</b>：每 300 次扫描（约 5min）由 leader 执行一次
 * {@code DELETE ... create_time < now() - 1h}，防止变更源表无限增长。
 */
@Slf4j
@Component
public class CredentialChangePoller implements SmartLifecycle {

    /** 轮询间隔（秒） */
    private static final long POLL_INTERVAL_SECONDS = 1;

    /** 单批消费上限（id 水印后升序限量） */
    private static final int BATCH_LIMIT = 100;

    /** 每 N 次扫描触发一次记录清理（1s 间隔 ≈ 5min） */
    private static final int CLEAN_EVERY_SCANS = 300;

    /** 变更源记录保留时长（1h 足够支撑跨节点收敛，Spec 2026-08-11 §4） */
    private static final long RETENTION_MS = TimeUnit.HOURS.toMillis(1);

    /** 线程池名：线程工厂与停机日志共用，避免两处字符串漂移 */
    private static final String POOL_NAME = "credential-change-poller";

    private final CredentialRep credentialRep;

    private final CredentialService credentialService;

    /** leader 门控仅用于记录清理（纯 housekeeping），消费路径不使用 */
    private final ScheduleLeaderElector leaderElector;

    private volatile boolean running = false;

    /** 进程内独立消费水印（启动置 max(id)，不回放历史） */
    private volatile long watermark = 0;

    /** 扫描计数：每 CLEAN_EVERY_SCANS 次触发一次记录清理（仅轮询单线程访问，无需原子） */
    private int scanCount = 0;

    private ScheduledExecutorService pollExecutor;

    public CredentialChangePoller(CredentialRep credentialRep, CredentialService credentialService,
                                  ScheduleLeaderElector leaderElector) {
        this.credentialRep = credentialRep;
        this.credentialService = credentialService;
        this.leaderElector = leaderElector;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        watermark = credentialRep.maxChangeId();
        pollExecutor = MdcExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, POOL_NAME);
            thread.setDaemon(true);
            return thread;
        }));
        pollExecutor.scheduleWithFixedDelay(this::pollOnce, POLL_INTERVAL_SECONDS,
                POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("CredentialChangePoller started, watermark initialized to {}", watermark);
    }

    /**
     * 单次轮询：消费变更源（无 leader 门控）+ 周期清理（leader 门控）。
     */
    void pollOnce() {
        try {
            consume();
            cleanupIfDue();
        } catch (Exception e) {
            log.error("credential change poll once failed, keep going", e);
        }
    }

    /** 消费：id 水印后升序 LIMIT 100，逐条失效本节点缓存，水印推进到批次最大 id */
    private void consume() {
        List<CredentialChange> changes = credentialRep.listChangesAfter(watermark, BATCH_LIMIT);
        for (CredentialChange change : changes) {
            try {
                credentialService.invalidate(change.getApplicationName(), change.getEnv());
            } catch (Exception e) {
                // 单条失败隔离：不阻断整批消费（参照 QueueReconciler.applyChange 语义）
                log.warn("credential change invalidate failed, changeId: {}, keep going", change.getId(), e);
            }
            watermark = change.getId();
        }
    }

    /** 记录清理：每 CLEAN_EVERY_SCANS 次扫描触发一次，仅 leader 执行（纯 housekeeping） */
    private void cleanupIfDue() {
        scanCount++;
        if (scanCount < CLEAN_EVERY_SCANS) {
            return;
        }
        scanCount = 0;
        if (!leaderElector.isLeader()) {
            return;
        }
        try {
            int deleted = credentialRep.deleteChangesBefore(
                    new Date(System.currentTimeMillis() - RETENTION_MS));
            if (deleted > 0) {
                log.info("credential change records cleaned, deleted={}", deleted);
            }
        } catch (Exception e) {
            log.error("credential change cleanup failed", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        ThreadPoolUtils.shutdownGracefully(pollExecutor, POOL_NAME, 2, TimeUnit.SECONDS);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
