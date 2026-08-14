package com.wly.job.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsRegistry} 薄封装单测（Spec §2.7）：使用 {@link SimpleMeterRegistry} 验证
 * counter / gauge / timer 三种注册方式都能产生可测量值。
 */
class MetricsRegistryTest {

    private final MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());

    @Test
    void counterIncrementsAndIsQueryable() {
        Counter counter = metrics.counter(MetricsRegistry.JOB_CALLBACK_SUCCESS);

        counter.increment();
        counter.increment(3);

        assertEquals(4, metrics.counter(MetricsRegistry.JOB_CALLBACK_SUCCESS).count());
    }

    @Test
    void counterWithTagsCreatesDistinctSeries() {
        Counter tagged = metrics.counter(MetricsRegistry.JOB_REC_SAVE_FAILURE, "op", "update");

        tagged.increment();

        assertEquals(1, metrics.counter(MetricsRegistry.JOB_REC_SAVE_FAILURE, "op", "update").count());
        // 不同 tags 是独立序列，同名无 tags 的计数不受影响
        assertEquals(0, metrics.counter(MetricsRegistry.JOB_REC_SAVE_FAILURE).count());
    }

    @Test
    void gaugeReflectsLatestSupplierValue() {
        AtomicLong backlog = new AtomicLong(3);

        Gauge gauge = metrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, backlog::doubleValue);
        assertEquals(3.0, gauge.value(), 0.001);

        backlog.set(10);
        assertEquals(10.0, gauge.value(), 0.001);
    }

    @Test
    void gaugeSurvivesGcThroughCompositeRegistry() {
        // 运行时 Spring Boot 注入的是 CompositeMeterRegistry（子含 PrometheusMeterRegistry），
        // CompositeGauge 对对象仅持弱引用，要求 MetricsRegistry 自行保持强引用，否则 GC 后值变 NaN
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        MetricsRegistry compositeMetrics = new MetricsRegistry(composite);
        AtomicLong backlog = new AtomicLong(3);
        compositeMetrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, backlog::doubleValue);

        // 多次强 GC 后仍应读到可测量值（而非 NaN）
        for (int i = 0; i < 5; i++) {
            System.gc();
        }
        Gauge gauge = compositeMetrics.gauge(MetricsRegistry.JOB_QUEUE_BACKLOG, backlog::doubleValue);
        assertEquals(3.0, gauge.value(), 0.001);
    }

    @Test
    void timerRecordsElapsedTime() {
        Timer timer = metrics.timer(MetricsRegistry.JOB_REQUEST_LATENCY);

        Timer.Sample sample = Timer.start();
        sample.stop(timer);

        assertTrue(timer.count() == 1);
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) >= 0);
    }
}
