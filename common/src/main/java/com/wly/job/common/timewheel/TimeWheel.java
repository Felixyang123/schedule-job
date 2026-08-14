package com.wly.job.common.timewheel;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 单层时间轮（Hashed Timing Wheel）抽象基类：以固定间隔 tick 划分时间刻度，
 * wheelSize 个槽构成一圈，槽内按绝对目标 tick 分组存放任务。
 *
 * <p>加入任务时，基于当前逻辑 tick 与剩余延迟计算 {@code targetTick}；时钟推进到该 tick 时
 * 直接摘取对应分组。任务不会因不足一个 tick 的延迟落入已消费槽，也无需维护 remainingRounds。
 * {@link #add}/{@link #remove}/{@link #getAndRemove}/{@link #clear} 由同一把锁保护。
 */
@Slf4j
public abstract class TimeWheel<T> {

    private final long tickMs;

    private final int wheelSize;

    /** 槽位 ->（绝对目标 tick -> 任务列表） */
    private final List<Map<Long, List<T>>> entries;

    /** 任务 -> 每次加入对应的 targetTick；同一任务可重复加入，remove 每次只删一份 */
    private final Map<T, Deque<Long>> taskTicks = new HashMap<>();

    private final long startMs;

    /** 最后一个已消费的逻辑 tick */
    private long currentTick;

    private volatile boolean stop;

    private final ReentrantLock lock = new ReentrantLock();

    public TimeWheel(int tick, int wheelSize) {
        if (tick <= 0 || wheelSize <= 0) {
            throw new IllegalArgumentException("tick and wheelSize must be positive");
        }
        this.tickMs = TimeUnit.SECONDS.toMillis(tick);
        this.wheelSize = wheelSize;
        this.entries = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            this.entries.add(new HashMap<>());
        }
        this.startMs = System.currentTimeMillis();
        this.currentTick = 0L;
        this.stop = false;
    }

    /**
     * 按绝对到期毫秒加入任务。
     *
     * <p>目标 tick 由「当前逻辑 tick + 向上取整的剩余延迟」推导，最早落在下一个未消费 tick：
     * 不足一个 tick 的未来任务不会被归入已消费槽而空等一整圈；已过期任务同样安排到下一 tick 立即触发。
     */
    public void add(T item, long expire) {
        lock.lock();
        try {
            long now = currentTimeMillis();
            long baseTick = Math.max(currentTick, elapsedTicks(now));
            long delayTicks = Math.max(1L, ceilDiv(Math.max(0L, expire - now), tickMs));
            long targetTick = baseTick + delayTicks;
            entries.get(slot(targetTick)).computeIfAbsent(targetTick, ignored -> new ArrayList<>()).add(item);
            taskTicks.computeIfAbsent(item, ignored -> new ArrayDeque<>()).addLast(targetTick);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出并移除指定时刻对应逻辑 tick 的任务。主要供测试和时钟循环使用。
     *
     * @return 到期任务；该 tick 无任务时返回 null
     */
    public List<T> getAndRemove(long ms) {
        lock.lock();
        try {
            long targetTick = elapsedTicks(ms);
            return removeTick(targetTick);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 移除该任务的一次加入记录（按反向索引定位槽位，与调用时刻无关）。
     * 同一任务多次加入时，每次调用只摘除最早的一份。
     */
    public boolean remove(T item) {
        lock.lock();
        try {
            Deque<Long> ticks = taskTicks.get(item);
            if (ticks == null || ticks.isEmpty()) {
                return false;
            }
            long targetTick = ticks.removeFirst();
            if (ticks.isEmpty()) {
                taskTicks.remove(item);
            }
            Map<Long, List<T>> slotEntries = entries.get(slot(targetTick));
            List<T> items = slotEntries.get(targetTick);
            if (items == null) {
                return false;
            }
            boolean removed = items.remove(item);
            if (items.isEmpty()) {
                slotEntries.remove(targetTick);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /** 恢复时钟驱动：清除停止标志，已有任务与逻辑 tick 均保留，支持同一实例停止后重启。 */
    public void start() {
        this.stop = false;
    }

    public void stop() {
        this.stop = true;
    }

    public void clear() {
        lock.lock();
        try {
            entries.forEach(Map::clear);
            taskTicks.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 时钟主循环：逐 tick 摘取到期任务交给回调处理。
     *
     * <p>单个 tick 的回调异常只记录日志，不得杀死常驻时钟线程；任务级重试策略由上层调度组件负责。
     * 调用前需确保时间轮处于运行态（新建实例默认运行；停止后须先调用 {@link #start()}）。
     */
    public void clock(Function<List<T>, List<T>> task) {
        while (!stop) {
            long nowTick = elapsedTicks(currentTimeMillis());
            while (!stop && currentTick < nowTick) {
                List<T> items;
                lock.lock();
                try {
                    currentTick++;
                    items = removeTick(currentTick);
                } finally {
                    lock.unlock();
                }
                if (items != null) {
                    try {
                        List<T> retryItems = task.apply(items);
                        if (retryItems != null) {
                            retryItems.forEach(this::add);
                        }
                    } catch (RuntimeException e) {
                        log.error("Time wheel callback failed, tick: {}, itemCount: {}",
                                currentTick, items.size(), e);
                    }
                }
            }
            long interval = (currentTick + 1L) * tickMs - (currentTimeMillis() - startMs);
            if (interval > 0) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    protected abstract void add(T item);

    private List<T> removeTick(long targetTick) {
        List<T> items = entries.get(slot(targetTick)).remove(targetTick);
        if (items != null) {
            for (T item : items) {
                Deque<Long> ticks = taskTicks.get(item);
                if (ticks != null) {
                    ticks.removeFirstOccurrence(targetTick);
                    if (ticks.isEmpty()) {
                        taskTicks.remove(item);
                    }
                }
            }
        }
        return items;
    }

    private long elapsedTicks(long timeMs) {
        return Math.max(0L, Math.floorDiv(timeMs - startMs, tickMs));
    }

    private int slot(long targetTick) {
        return (int) Math.floorMod(targetTick, wheelSize);
    }

    private static long ceilDiv(long dividend, long divisor) {
        return dividend == 0L ? 0L : 1L + (dividend - 1L) / divisor;
    }
}
