package com.wly.job.server.credential;

import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.metrics.MetricsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 凭证过期预警监控（Spec 2026-08-14 §6）：60s 扫描 ACTIVE 版本，
 * 剩余有效期 ≤ 30 天时上报 Gauge {@code job.credential.expiring{application,env,version} = 剩余天数}。
 *
 * <p><b>判定依据版本行 {@code expire_time}</b>（不得用缓存 TTL 代替，Spec 2026-08-11 §4 硬性约束 3）；
 * 30/7/1 天阈值告警由 Prometheus 告警规则消费本指标实现，代码只上报剩余天数（可负，表示已过期）。
 *
 * <p><b>leader 门控上报</b>：多节点重复上报同值无意义，仅主节点采集。
 * 每个 (application, env, version) 组合只注册一次 Gauge，supplier 读取共享 {@code remainingDays}
 * （Gauge 重复注册会复用首次 supplier，见 {@link MetricsRegistry#gauge(String, String[], java.util.function.Supplier)}）。
 */
@Slf4j
@Component
public class CredentialExpiryMonitor implements SmartLifecycle {

    /** 扫描间隔（秒） */
    private static final long SCAN_INTERVAL_SECONDS = 60;

    /** 上报阈值：剩余天数 ≤ 30 才设置值（>30 移除，Prometheus 下序列消失） */
    private static final double REPORT_THRESHOLD_DAYS = 30.0;

    /** 线程池名：线程工厂与停机日志共用，避免两处字符串漂移 */
    private static final String POOL_NAME = "credential-expiry-monitor";

    private final CredentialRep credentialRep;

    private final MetricsRegistry metrics;

    private final ScheduleLeaderElector leaderElector;

    /** 已注册 Gauge 的 (app:env:version) 集合（每个组合只注册一次） */
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();

    /** 剩余天数共享值：Gauge supplier 读取（键 app:env:version，不存在时返回 NaN） */
    private final Map<String, Double> remainingDays = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    private ScheduledExecutorService scanExecutor;

    public CredentialExpiryMonitor(CredentialRep credentialRep, MetricsRegistry metrics,
                                   ScheduleLeaderElector leaderElector) {
        this.credentialRep = credentialRep;
        this.metrics = metrics;
        this.leaderElector = leaderElector;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        scanExecutor = MdcExecutorService.wrap(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, POOL_NAME);
            thread.setDaemon(true);
            return thread;
        }));
        scanExecutor.scheduleWithFixedDelay(this::scanOnce, SCAN_INTERVAL_SECONDS,
                SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("CredentialExpiryMonitor started, scan interval: {}s", SCAN_INTERVAL_SECONDS);
    }

    /** 单次扫描：仅 leader 上报；异常隔离不中断周期 */
    void scanOnce() {
        try {
            if (leaderElector.isLeader()) {
                scan();
            }
        } catch (Exception e) {
            log.error("credential expiry scan failed, keep going", e);
        }
    }

    private void scan() {
        List<CredentialVersion> actives = credentialRep.listActiveVersions();
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        for (CredentialVersion version : actives) {
            if (version.getExpireTime() == null) {
                continue;
            }
            Credential identity = credentialRep.findIdentityById(version.getCredentialId());
            if (identity == null) {
                continue;
            }
            String key = identity.getApplicationName() + ":" + identity.getEnv() + ":" + version.getVersion();
            seen.add(key);
            double days = (version.getExpireTime().getTime() - now) / (double) TimeUnit.DAYS.toMillis(1);
            if (days <= REPORT_THRESHOLD_DAYS) {
                remainingDays.put(key, days);
                registerGaugeOnce(key, identity.getApplicationName(), identity.getEnv(), version.getVersion());
            } else {
                remainingDays.remove(key);
            }
        }
        // 清理已不在 ACTIVE 集合的过期键（吊销/轮换后序列消失）
        remainingDays.keySet().removeIf(key -> !seen.contains(key));
    }

    private void registerGaugeOnce(String key, String applicationName, String env, int version) {
        if (registeredGauges.add(key)) {
            metrics.gauge(MetricsRegistry.JOB_CREDENTIAL_EXPIRING,
                    new String[]{"application", applicationName, "env", env,
                            "version", String.valueOf(version)},
                    () -> remainingDays.getOrDefault(key, Double.NaN));
        }
    }

    @Override
    public void stop() {
        running = false;
        ThreadPoolUtils.shutdownGracefully(scanExecutor, POOL_NAME, 2, TimeUnit.SECONDS);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
