package com.wly.job.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Micrometer 指标薄封装（Spec §2.7 可观测性）：统一指标入口。
 * <p>
 * 指标统一使用 {@code job.} 前缀（Prometheus 命名约定），经由 {@code /actuator/prometheus} 暴露；
 * 度量均为只读观察，不改变任何调度语义。具体埋点见：
 * <ul>
 *   <li>{@link com.wly.job.server.schedule.JobScheduler}：队列积压 / 变更源滞后 Gauge</li>
 *   <li>{@link com.wly.job.server.client.callback.ScheduleRecCallback}：回调成功/失败 Counter</li>
 *   <li>{@link com.wly.job.server.client.ScheduleJobClient}：派发延迟 Timer</li>
 *   <li>{@link com.wly.job.server.schedule.ScheduleRecQueue}：落库失败 / 丢弃 Counter</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MetricsRegistry {

    /** 调度引擎队列积压数（调度队列中待执行的 ScheduleJob 数量，Gauge） */
    public static final String JOB_QUEUE_BACKLOG = "job.scheduler.queue.backlog";

    /** 变更源消费水印滞后（job_change 表 maxId - 本地已消费水印，Gauge） */
    public static final String JOB_CHANGE_LAG = "job.scheduler.change.lag";

    /** RPC 回调成功计数（Counter） */
    public static final String JOB_CALLBACK_SUCCESS = "job.callback.success";

    /** RPC 回调失败计数（Counter） */
    public static final String JOB_CALLBACK_FAILURE = "job.callback.failure";

    /** 派发延迟：send() 入口到 Netty 写包（Timer，秒） */
    public static final String JOB_DISPATCH_LATENCY = "job.dispatch.latency";

    /** ScheduleRec 落库失败计数（saveBatch/update 重试耗尽，Counter） */
    public static final String JOB_REC_SAVE_FAILURE = "job.rec.save.failure";

    /** ScheduleRec 队列打满被丢弃的记录计数（Counter） */
    public static final String JOB_REC_DROPPED = "job.rec.dropped";

    /** Worker 心跳失败计数（Counter，预留：core 模块无 micrometer，本轮未接线，见 Task G 报告） */
    public static final String JOB_HEARTBEAT_FAILURE = "job.heartbeat.failure";

    private final MeterRegistry meterRegistry;

    /**
     * 已注册 Gauge 的对象强引用集合：运行时注入的 {@link CompositeMeterRegistry} 对 Gauge 对象
     * 仅持弱引用（见 CompositeGauge），若不在此处保持强引用，对象被 GC 后 Gauge 值将变为 NaN。
     */
    private final Set<Supplier<Double>> gaugeHolders = ConcurrentHashMap.newKeySet();

    /**
     * 获取（必要时注册）计数器。同名同 tags 时由 Micrometer 返回既有实例，可重复调用。
     */
    public Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    /**
     * 注册 Gauge：值由 {@code supplier} 在抓取时求值（不缓存，抓取即读）。
     * <p>注意：{@code supplier} 会被本类强引用（防 GC 导致 NaN），请勿传入可被回收的短生命周期对象。
     */
    public Gauge gauge(String name, Supplier<Double> supplier) {
        gaugeHolders.add(supplier);
        return Gauge.builder(name, supplier, s -> s.get().doubleValue()).register(meterRegistry);
    }

    /**
     * 获取（必要时注册）Timer。
     */
    public Timer timer(String name) {
        return Timer.builder(name).register(meterRegistry);
    }
}
