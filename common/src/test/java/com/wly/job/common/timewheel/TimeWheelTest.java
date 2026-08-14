package com.wly.job.common.timewheel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWheelTest {

    /** 测试时间轮：时钟由 {@link #advance(long)} 按相对 startMs 的偏移显式推进，避免依赖真实时间。 */
    private static final class StringWheel extends TimeWheel<String> {

        private final long startMs;

        private volatile long now;

        private StringWheel(int tick, int wheelSize) {
            super(tick, wheelSize);
            this.startMs = readStartMs(this);
            this.now = this.startMs;
        }

        @Override
        protected void add(String item) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        protected long currentTimeMillis() {
            return now;
        }

        /** 把逻辑时钟推进到 startMs + offsetMs */
        void advance(long offsetMs) {
            this.now = this.startMs + offsetMs;
        }

        /** startMs + offsetMs 对应的绝对毫秒 */
        long at(long offsetMs) {
            return this.startMs + offsetMs;
        }

        private static long readStartMs(TimeWheel<?> wheel) {
            try {
                var field = TimeWheel.class.getDeclaredField("startMs");
                field.setAccessible(true);
                return field.getLong(wheel);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void addAndRemoveWithFutureExpire() {
        StringWheel wheel = new StringWheel(1, 60);

        wheel.add("job-1", wheel.at(10_000));

        assertTrue(wheel.remove("job-1"));
        assertNull(wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void futureExpireLandsOnCeilingTick() {
        StringWheel wheel = new StringWheel(1, 60);

        wheel.add("job-1", wheel.at(10_000));

        assertEquals(List.of("job-1"), wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void subTickFutureIsScheduledOnNextTickInsteadOfCurrentSlot() {
        StringWheel wheel = new StringWheel(1, 60);

        // 300ms 后到期：向上取整到下一个未消费 tick，而非落在已消费的当前槽后空等一整圈
        wheel.add("sub-tick", wheel.at(300));

        assertEquals(List.of("sub-tick"), wheel.getAndRemove(wheel.at(1_000)));
    }

    @Test
    void overdueItemIsScheduledOnNextTick() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.advance(10_000);

        // 已过期任务安排到下一个未消费 tick 立即触发
        wheel.add("job-overdue", wheel.at(5_000));

        assertEquals(List.of("job-overdue"), wheel.getAndRemove(wheel.at(11_000)));
    }

    @Test
    void removeReturnsFalseForUnknownItem() {
        StringWheel wheel = new StringWheel(1, 60);

        assertFalse(wheel.remove("unknown"));
    }

    @Test
    void removeDropsOneOccurrencePerCallForRepeatedAdd() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("job-1", wheel.at(5_000));
        wheel.add("job-1", wheel.at(10_000));

        assertTrue(wheel.remove("job-1"));

        assertNull(wheel.getAndRemove(wheel.at(5_000)));
        assertEquals(List.of("job-1"), wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void addStillWorksAfterSlotWasEmptied() {
        StringWheel wheel = new StringWheel(1, 60);

        wheel.add("first", wheel.at(10_000));
        assertEquals(List.of("first"), wheel.getAndRemove(wheel.at(10_000)));
        wheel.add("second", wheel.at(10_000));

        assertEquals(List.of("second"), wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void addStillWorksAfterClear() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("before-clear", wheel.at(10_000));
        wheel.clear();

        wheel.add("after-clear", wheel.at(10_000));

        assertEquals(List.of("after-clear"), wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void clearEmptiesAllSlots() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("job-1", wheel.at(10_000));

        wheel.clear();

        assertNull(wheel.getAndRemove(wheel.at(10_000)));
    }

    @Test
    void clearAlsoDropsRemoveIndexSoRemoveReturnsFalse() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("job-1", wheel.at(10_000));

        wheel.clear();

        assertFalse(wheel.remove("job-1"), "clear 后反向索引不应残留已丢弃任务");
    }

    @Test
    void tasksInSameSlotButDifferentRoundsAreIsolated() {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("first-round", wheel.at(10_000));
        wheel.add("second-round", wheel.at(70_000));

        assertEquals(List.of("first-round"), wheel.getAndRemove(wheel.at(10_000)));
        assertEquals(List.of("second-round"), wheel.getAndRemove(wheel.at(70_000)));
    }

    @Test
    void clockProcessesEveryElapsedTickWithControlledTime() throws Exception {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("tick-1", wheel.at(1_000));
        wheel.add("tick-2", wheel.at(2_000));
        wheel.add("tick-3", wheel.at(3_000));
        wheel.advance(3_000);
        List<String> handled = new ArrayList<>();
        CountDownLatch allHandled = new CountDownLatch(3);
        Thread clock = new Thread(() -> wheel.clock(items -> {
            handled.addAll(items);
            items.forEach(ignored -> allHandled.countDown());
            if (allHandled.getCount() == 0) {
                wheel.stop();
            }
            return null;
        }), "controlled-time-wheel-test");

        clock.start();

        assertTrue(allHandled.await(1, TimeUnit.SECONDS), "一次时钟推进应逐 tick 消费全部到期任务");
        clock.interrupt();
        clock.join(1_000);
        assertFalse(clock.isAlive());
        assertEquals(List.of("tick-1", "tick-2", "tick-3"), handled);
    }

    @Test
    void concurrentAddRemoveAndClockDoesNotLoseOrDuplicateItems() throws Exception {
        StringWheel wheel = new StringWheel(1, 60);
        int pendingCount = 300;
        int addedCount = 300;
        for (int i = 0; i < pendingCount; i++) {
            wheel.add("pending-" + i, wheel.at(1_000));
        }
        wheel.advance(100_000);

        Set<String> removed = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<String, AtomicInteger> dispatched = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> adding = workers.submit(() -> {
            await(start);
            for (int i = 0; i < addedCount; i++) {
                wheel.add("added-" + i, wheel.at(101_000));
            }
        });
        Future<?> removing = workers.submit(() -> {
            await(start);
            for (int i = 0; i < pendingCount; i++) {
                String item = "pending-" + i;
                if (wheel.remove(item)) {
                    removed.add(item);
                }
            }
        });
        Thread clock = new Thread(() -> {
            await(start);
            wheel.clock(items -> {
                items.forEach(item -> dispatched.computeIfAbsent(item, ignored -> new AtomicInteger()).incrementAndGet());
                return null;
            });
        }, "concurrent-time-wheel-test");

        clock.start();
        start.countDown();
        adding.get(1, TimeUnit.SECONDS);
        removing.get(1, TimeUnit.SECONDS);
        wheel.stop();
        clock.interrupt();
        clock.join(1_000);
        workers.shutdownNow();

        assertFalse(clock.isAlive());
        assertEquals(pendingCount, removed.size() + dispatched.size(), "每个原任务必须恰好被移除或派发");
        assertTrue(removed.stream().noneMatch(dispatched::containsKey), "移除成功的任务不得同时派发");
        assertTrue(dispatched.values().stream().allMatch(count -> count.get() == 1), "任务不得重复派发");
        for (int i = 0; i < addedCount; i++) {
            assertTrue(wheel.remove("added-" + i), "并发加入的任务不得丢失");
        }
    }

    @Test
    void callbackFailureDoesNotKillClock() throws Exception {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("first", wheel.at(1_000));
        wheel.add("second", wheel.at(2_000));
        wheel.advance(2_000);
        AtomicBoolean firstBatch = new AtomicBoolean(true);
        AtomicBoolean secondBatchHandled = new AtomicBoolean();
        Thread clock = new Thread(() -> wheel.clock(items -> {
            if (firstBatch.getAndSet(false)) {
                throw new IllegalStateException("callback failure");
            }
            secondBatchHandled.set(true);
            wheel.stop();
            return null;
        }));

        clock.start();
        clock.join(5_000);

        assertFalse(clock.isAlive(), "时钟线程应在处理后续 tick 后正常退出");
        assertTrue(secondBatchHandled.get(), "单个 tick 回调异常不应终止后续 tick 处理");
    }

    @Test
    void stoppedWheelCanBeStartedAgain() throws Exception {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.stop();

        wheel.start();
        wheel.add("after-restart", wheel.at(1_000));
        wheel.advance(1_000);
        AtomicBoolean handled = new AtomicBoolean();
        Thread clock = new Thread(() -> wheel.clock(items -> {
            handled.set(true);
            wheel.stop();
            return null;
        }));

        clock.start();
        clock.join(5_000);

        assertFalse(clock.isAlive(), "重启后的时钟线程应能正常退出");
        assertTrue(handled.get(), "stop 后调用 start 应恢复时钟驱动");
    }

    @Test
    void clockDoesNotRunWhenStoppedWithoutRestart() throws Exception {
        StringWheel wheel = new StringWheel(1, 60);
        wheel.add("pending", wheel.at(1_000));
        wheel.advance(1_000);
        wheel.stop();
        AtomicBoolean handled = new AtomicBoolean();
        Thread clock = new Thread(() -> wheel.clock(items -> {
            handled.set(true);
            return null;
        }));

        clock.start();
        clock.join(5_000);

        assertFalse(clock.isAlive(), "已停止的时间轮 clock 应立即返回");
        assertFalse(handled.get(), "已停止的时间轮不应触发回调");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
