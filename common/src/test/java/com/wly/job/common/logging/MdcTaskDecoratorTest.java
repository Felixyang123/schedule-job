package com.wly.job.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
