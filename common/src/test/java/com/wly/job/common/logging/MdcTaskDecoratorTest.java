package com.wly.job.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcTaskDecoratorTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mdcValueIsVisibleInsideDecoratedTask() {
        MDC.put("requestId", "req-001");

        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = MdcTaskDecorator.decorate(() -> seen.set(MDC.get("requestId")));

        task.run();

        assertEquals("req-001", seen.get(), "父线程 put 的 MDC 值在任务内应可见");
    }

    @Test
    void taskMdcPutsDoNotLeakToReusedThread() throws Exception {
        MDC.put("requestId", "req-001");
        AtomicReference<String> seenInSecond = new AtomicReference<>();
        AtomicBoolean leaked = new AtomicBoolean();

        ExecutorService wrapped = MdcTaskDecorator.wrap(Executors.newSingleThreadExecutor());
        try {
            Future<?> first = wrapped.submit(() -> MDC.put("leakKey", "leak-value"));
            first.get();
            Future<?> second = wrapped.submit(() -> {
                seenInSecond.set(MDC.get("requestId"));
                if (MDC.get("leakKey") != null) {
                    leaked.set(true);
                }
            });
            second.get();
        } finally {
            wrapped.shutdown();
        }

        assertEquals("req-001", seenInSecond.get(), "复用线程上的第二个任务仍应可见父线程快照");
        assertFalse(leaked.get(), "第一个任务写入的 MDC 不应泄漏到复用线程上的第二个任务");
    }

    @Test
    void scheduledFixedDelayTaskRestoresSnapshotAcrossRuns() throws Exception {
        MDC.put("traceId", "trace-scheduled");
        AtomicReference<String> firstSeen = new AtomicReference<>();
        AtomicReference<String> secondSeen = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(2);
        ScheduledExecutorService wrapped = MdcExecutorService.wrap(
                Executors.newSingleThreadScheduledExecutor());
        try {
            wrapped.scheduleWithFixedDelay(() -> {
                if (ran.getCount() == 2) {
                    firstSeen.set(MDC.get("traceId"));
                    MDC.put("traceId", "mutated-inside-first-run");
                    MDC.put("leakKey", "must-not-leak");
                } else {
                    secondSeen.set(MDC.get("traceId") + ":" + MDC.get("leakKey"));
                }
                ran.countDown();
            }, 0L, 1L, TimeUnit.MILLISECONDS);
            assertTrue(ran.await(5, TimeUnit.SECONDS), "固定延迟任务应至少执行两轮");
        } finally {
            wrapped.shutdownNow();
        }

        assertEquals("trace-scheduled", firstSeen.get(), "首轮应传播提交线程 MDC 快照");
        assertEquals("trace-scheduled:null", secondSeen.get(), "第二轮应重新恢复快照且无首轮污染");
    }

    @Test
    void invokeAnyPropagatesMdcForBothOverloads() throws Exception {
        MDC.put("requestId", "invoke-any-id");
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newFixedThreadPool(2));
        try {
            assertEquals("invoke-any-id", wrapped.invokeAny(List.of(() -> MDC.get("requestId"))));
            assertEquals("invoke-any-id", wrapped.invokeAny(
                    List.of(() -> MDC.get("requestId")), 5, TimeUnit.SECONDS));
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void emptyParentContextClearsInsideWithoutException() {
        MDC.clear();

        Runnable task = MdcTaskDecorator.decorate(() -> {
            Map<String, String> inside = MDC.getCopyOfContextMap();
            assertTrue(inside == null || inside.isEmpty(), "父线程无 MDC 时任务内应为空");
            // clear 后 MDC 仍可正常写入与读取，不抛异常
            MDC.put("inside", "v");
            assertEquals("v", MDC.get("inside"));
        });

        task.run();
    }

    @Test
    void finallyRestoresParentSnapshotInNestedScenario() {
        MDC.put("requestId", "outer-id");
        Map<String, String> parentSnapshot = MDC.getCopyOfContextMap();

        Runnable outer = MdcTaskDecorator.decorate(() -> {
            assertEquals("outer-id", MDC.get("requestId"), "外层任务应可见父线程快照");

            // 内层任务在外层任务上下文中装饰，捕获的快照也是 {requestId=outer-id}
            Runnable inner = MdcTaskDecorator.decorate(() -> {
                MDC.put("innerKey", "inner-value");
                MDC.put("requestId", "inner-mutated");
            });
            inner.run();

            // 内层任务 finally 已恢复其捕获的快照，其写入不残留
            assertEquals("outer-id", MDC.get("requestId"));
            assertNull(MDC.get("innerKey"));

            MDC.put("requestId", "outer-mutated");
        });

        outer.run();

        // 外层任务 finally 恢复父线程原快照
        assertEquals(parentSnapshot, MDC.getCopyOfContextMap());
        assertEquals("outer-id", MDC.get("requestId"));
        assertNull(MDC.get("innerKey"));
    }

    // ---- null 参数即时失败契约：在提交/包装阶段抛 NPE，不依赖线程实际执行 ----

    @Test
    void executeNullFailsImmediately() {
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class, () -> wrapped.execute(null),
                    "execute(null) 应在提交前即时抛 NPE");
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void submitNullRunnableFailsImmediately() {
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class, () -> wrapped.submit((Runnable) null),
                    "submit((Runnable) null) 应在提交前即时抛 NPE");
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void submitNullCallableFailsImmediately() {
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class, () -> wrapped.submit((Callable<?>) null),
                    "submit((Callable) null) 应在提交前即时抛 NPE");
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void invokeAllWithNullMemberFailsImmediately() {
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class,
                    () -> wrapped.invokeAll(Arrays.asList((Callable<String>) () -> "ok", null)),
                    "invokeAll 含 null 成员应在包装阶段即时抛 NPE");
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void invokeAnyWithNullMemberFailsImmediately() {
        ExecutorService wrapped = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class,
                    () -> wrapped.invokeAny(Arrays.asList((Callable<String>) () -> "ok", null)),
                    "invokeAny 含 null 成员应在包装阶段即时抛 NPE");
        } finally {
            wrapped.shutdownNow();
        }
    }
}
