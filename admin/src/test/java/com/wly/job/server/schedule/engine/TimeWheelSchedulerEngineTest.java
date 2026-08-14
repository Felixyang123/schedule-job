package com.wly.job.server.schedule.engine;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.schedule.ScheduleJob;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWheelSchedulerEngineTest {

    private static JobView view() {
        return JobView.of(Job.builder().id(1L).name("j")
                .cron("0/5 * * * * ?").type(0).finished(0).build());
    }

    private static long nowNanos() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    @Test
    void clearEmptiesReadyQueue() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        engine.add(new ScheduleJob(view(), nowNanos() - 1_000_000L));

        engine.clear();

        assertTrue(engine.isEmpty());
    }

    @Test
    void removeDropsPendingJobFromWheel() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ScheduleJob pending = new ScheduleJob(view(), nowNanos() + TimeUnit.SECONDS.toNanos(10));
        engine.add(pending);

        assertTrue(engine.remove(pending), "未到期任务应能从时间轮精确摘除");
        assertFalse(engine.remove(pending), "重复移除应返回 false");
    }

    @Test
    void removeDropsExpiredJobFromReadyQueue() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ScheduleJob expired = new ScheduleJob(view(), nowNanos() - 1_000_000L);
        engine.add(expired);

        assertTrue(engine.remove(expired), "已到期任务应能从就绪队列摘除");
        assertTrue(engine.isEmpty());
    }

    @Test
    void clearAlsoDropsWheelRemoveIndex() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ScheduleJob pending = new ScheduleJob(view(), nowNanos() + TimeUnit.SECONDS.toNanos(10));
        engine.add(pending);

        engine.clear();

        assertFalse(engine.remove(pending), "clear 后时间轮反向索引不应残留条目");
    }

    @Test
    void engineCanBeRestartedAfterStop() throws Exception {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        try {
            engine.start();
            engine.stop();

            engine.start();
            engine.add(new ScheduleJob(view(), nowNanos() + TimeUnit.MILLISECONDS.toNanos(200)));

            assertNotNull(takeWithin(engine, 2_000), "重启后的引擎应能继续把到期任务投递到就绪队列");
        } finally {
            engine.stop();
        }
    }

    @Test
    void stopPreventsPendingJobFromBeingDispatched() throws Exception {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ExecutorService taker = Executors.newSingleThreadExecutor();
        try {
            engine.start();
            engine.add(new ScheduleJob(view(), nowNanos() + TimeUnit.MILLISECONDS.toNanos(200)));
            engine.stop();

            Future<ScheduleJob> result = taker.submit(engine::take);
            assertThrows(TimeoutException.class, () -> result.get(1_200, TimeUnit.MILLISECONDS),
                    "stop 后时钟不得继续消费并投递未到期任务");
            result.cancel(true);
        } finally {
            engine.stop();
            taker.shutdownNow();
        }
    }

    @Test
    void rapidRestartLeavesOnlyTheNewClockThreadRunning() throws Exception {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        try {
            engine.start();
            Thread firstClock = clockThreadOf(engine);

            engine.stop();
            engine.start();
            Thread secondClock = clockThreadOf(engine);

            assertNotSame(firstClock, secondClock);
            firstClock.join(500);
            assertFalse(firstClock.isAlive(), "start 返回前旧时钟线程必须已经退出");
            assertTrue(secondClock.isAlive(), "重启后应仅由新时钟线程驱动该引擎实例");
        } finally {
            engine.stop();
            Thread clock = clockThreadOf(engine);
            if (clock != null) {
                clock.join(500);
            }
        }
    }

    private static ScheduleJob takeWithin(TimeWheelSchedulerEngine engine, long timeoutMs) throws Exception {
        ExecutorService taker = Executors.newSingleThreadExecutor();
        Future<ScheduleJob> result = taker.submit(engine::take);
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            result.cancel(true);
            taker.shutdownNow();
        }
    }

    private static Thread clockThreadOf(TimeWheelSchedulerEngine engine) {
        try {
            Field field = TimeWheelSchedulerEngine.class.getDeclaredField("clockThread");
            field.setAccessible(true);
            return (Thread) field.get(engine);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
