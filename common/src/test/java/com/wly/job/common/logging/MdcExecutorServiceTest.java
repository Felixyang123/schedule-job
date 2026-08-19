package com.wly.job.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MdcExecutorService} 提交路径的 MDC 透传补测：submit(Runnable,T) / invokeAll（双重载）/
 * execute / submit(Runnable) / submit(Callable) 提交的任务必须携带提交线程的 MDC 快照；
 * 装饰任务执行结束后工作线程 MDC 必须恢复为空（防止污染复用线程）。
 *
 * <p>invokeAny 双重载、null 即时校验与装饰器恢复细节由 {@link MdcTaskDecoratorTest} 覆盖，不重复。
 */
class MdcExecutorServiceTest {

    private static final String KEY = "traceId";
    private static final String VALUE = "mdc-value";

    private final ExecutorService raw = Executors.newSingleThreadExecutor();
    private final MdcExecutorService wrapped = MdcExecutorService.wrap(raw);

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void shutdown() {
        wrapped.shutdownNow();
    }

    @Test
    void executePropagatesMdcSnapshot() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        withMdc(() -> wrapped.execute(() -> seen.set(MDC.get(KEY))));
        await(() -> seen.get() != null);

        assertEquals(VALUE, seen.get());
    }

    @Test
    void submitCallablePropagatesMdcSnapshot() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        withMdc(() -> {
            try {
                assertEquals("ok", wrapped.submit(() -> {
                    seen.set(MDC.get(KEY));
                    return "ok";
                }).get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(VALUE, seen.get());
    }

    @Test
    void submitRunnablePropagatesMdcSnapshot() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        withMdc(() -> {
            try {
                wrapped.submit(() -> seen.set(MDC.get(KEY))).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(VALUE, seen.get());
    }

    @Test
    void submitWithResultPropagatesMdcSnapshot() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        withMdc(() -> {
            try {
                // submit(Runnable, T)：任务与结果占位符都须正常工作，MDC 快照进入任务线程
                Future<String> future = wrapped.submit(() -> seen.set(MDC.get(KEY)), "done");
                assertEquals("done", future.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(VALUE, seen.get());
    }

    @Test
    void invokeAllPropagatesMdcSnapshotForBothOverloads() throws Exception {
        AtomicReference<String> seenPlain = new AtomicReference<>();
        AtomicReference<String> seenTimed = new AtomicReference<>();
        withMdc(() -> {
            try {
                List<Future<String>> plain = wrapped.invokeAll(List.of(
                        task("a", seenPlain), task("b", seenPlain)));
                assertEquals(List.of("a", "b"), results(plain));

                List<Future<String>> timed = wrapped.invokeAll(
                        List.of(task("c", seenTimed), task("d", seenTimed)), 5, TimeUnit.SECONDS);
                assertEquals(List.of("c", "d"), results(timed));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(VALUE, seenPlain.get(), "invokeAll 任务应携带 MDC 快照");
        assertEquals(VALUE, seenTimed.get(), "invokeAll(timeout) 任务应携带 MDC 快照");
    }

    @Test
    void decoratedTaskRestoresWorkerThreadMdcAfterCompletion() throws Exception {
        // 先经包装执行器跑一个写 MDC 的装饰任务，再用裸执行器在同一工作线程观察：
        // 装饰器若未在 finally 恢复，观察任务会读到残留值
        CountDownLatch decoratedDone = new CountDownLatch(1);
        AtomicReference<String> afterRestore = new AtomicReference<>("unset");
        withMdc(() -> wrapped.execute(() -> {
            MDC.put("leakKey", "leak-value");
            decoratedDone.countDown();
        }));
        assertTrue(decoratedDone.await(5, TimeUnit.SECONDS), "装饰任务应完成");

        raw.submit(() -> afterRestore.set(MDC.get("leakKey"))).get(5, TimeUnit.SECONDS);

        assertNull(afterRestore.get(), "装饰任务结束后工作线程 MDC 必须恢复为空");
    }

    // ---------- 工具 ----------

    private static void withMdc(Runnable body) {
        MDC.put(KEY, VALUE);
        try {
            body.run();
        } finally {
            MDC.clear();
        }
    }

    private static Callable<String> task(String result, AtomicReference<String> seen) {
        return () -> {
            seen.set(MDC.get(KEY));
            return result;
        };
    }

    private static List<String> results(List<Future<String>> futures) throws Exception {
        return futures.stream().map(f -> {
            try {
                return f.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    private static void await(CheckedBoolean condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.get(), "任务应在超时前执行完毕");
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
