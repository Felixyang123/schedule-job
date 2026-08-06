package com.wly.job.common.timewheel;

import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 单层时间轮（Hashed Timing Wheel）抽象基类：以固定间隔 tick 划分时间刻度，
 * wheelSize 个槽构成一圈，槽内按圈数（epoch）分层存放任务，用于到期任务的触发与延迟调度，
 * 可作为 {@code TimeWheelSchedulerEngine} 的底层支撑。
 * <p>
 * 线程模型：{@link #add}/{@link #remove}/{@link #getAndRemove} 之间以可重入锁互斥保护，
 * 内部为线程安全结构；{@link #clock} 是唯一的驱动主循环线程，逐 tick 取出到期任务，
 * 交给子类回调函数处理（含失败/未完成任务的重新入队）。
 * <p>
 * 扩展点：子类覆写 {@link #currentTimeMillis()} 可注入测试时钟；实现抽象方法
 * {@link #add(T)} 定义重新入队的落点。
 */
public abstract class TimeWheel<T> {
    /**
     * 槽间隔（秒）
     */
    private final int tick;

    /**
     * 槽数：一圈含 wheelSize 个槽，槽位 = 总 tick 数 % wheelSize
     */
    private final int wheelSize;

    /**
     * 环形槽数组：下标对应槽位，元素为「圈数 -> 任务列表」的映射
     */
    private final List<Entry<T>> entries;

    private final long tickMs;

    /**
     * 时钟起始时刻（毫秒），所有到期时间均相对它计算偏移
     */
    private final long startMs;

    private volatile boolean stop;

    private final ReentrantLock lock = new ReentrantLock();

    public TimeWheel(int tick, int wheelSize) {
        this.tick = tick;
        this.tickMs = TimeUnit.SECONDS.toMillis(tick);
        this.wheelSize = wheelSize;
        this.entries = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            this.entries.add(null);
        }
        this.startMs = System.currentTimeMillis();
        this.stop = false;
    }

    public void add(T item, long expire) {
        lock.lock();
        try {
            long seconds = (expire - startMs) / 1000;

            long ticks = seconds / tick;

            // 过期/超期任务防止负索引：归入当前槽位，下一个 tick 立即取出
            long currentTicks = Math.max(0L, (currentTimeMillis() - startMs) / 1000 / tick);
            if (ticks < currentTicks) {
                ticks = currentTicks;
            }

            // 槽位 = 总 tick 数对圈大小取模；圈数 epoch = 总 tick 数整除圈大小，
            // 同一槽位在不同圈数的任务以 epoch 区分，互不覆盖
            int index = (int) (ticks % wheelSize);

            int epoch = (int) ticks / wheelSize;

            Entry<T> entry = entries.get(index);

            if (entry == null) {
                entry = new Entry<>(new HashMap<>());
                entries.set(index, entry);
            }

            entry.itemsMap.computeIfAbsent(epoch, k -> new ArrayList<>()).add(item);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出并移除指定时刻（绝对毫秒）对应的槽位中该圈的所有到期任务。
     * 取走后若槽位为空则释放该槽，供 {@link #clock} 主循环消费。
     *
     * @return 到期任务列表；该槽位无任务时返回 null
     */
    public List<T> getAndRemove(long ms) {
        lock.lock();
        try {
            long seconds = (ms - startMs) / 1000;

            long ticks = seconds / tick;

            int index = (int) (ticks % wheelSize);

            int epoch = (int) ticks / wheelSize;

            Entry<T> entry = entries.get(index);

            if (entry == null) {
                return null;
            }

            List<T> items = entry.itemsMap.remove(epoch);
            if (items != null && entry.itemsMap.isEmpty()) {
                entries.set(index, null);
            }
            return items;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从时间轮中移除指定任务（按过期时间定位槽位）。
     *
     * @return 是否成功移除
     */
    public boolean remove(T item, long expire) {
        lock.lock();
        try {
            long seconds = (expire - startMs) / 1000;
            long ticks = seconds / tick;

            int index = (int) (ticks % wheelSize);
            int epoch = (int) ticks / wheelSize;

            Entry<T> entry = entries.get(index);
            if (entry == null) {
                return false;
            }

            List<T> items = entry.itemsMap.get(epoch);
            if (items == null) {
                return false;
            }
            boolean removed = items.remove(item);
            if (items.isEmpty()) {
                entry.itemsMap.remove(epoch);
                if (entry.itemsMap.isEmpty()) {
                    entries.set(index, null);
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        this.stop = true;
    }

    public void clear() {
        lock.lock();
        try {
            for (int i = 0; i < entries.size(); i++) {
                entries.set(i, null);
            }
        } finally {
            lock.unlock();
        }
    }

    @SneakyThrows
    public void clock(Function<List<T>, List<T>> task) {
        long lastTickMs = startMs;
        while (!stop) {
            long now = currentTimeMillis();
            // 一次补偿所有错过的 tick，避免线程被阻塞/暂停后任务延迟一个轮转周期
            while (lastTickMs + tickMs <= now) {
                lastTickMs += tickMs;
                List<T> items = getAndRemove(lastTickMs);
                if (items != null) {
                    List<T> apply = task.apply(items);
                    if (apply != null) {
                        apply.forEach(this::add);
                    }
                }
            }
            long interval = (lastTickMs + tickMs) - currentTimeMillis();
            if (interval > 0) {
                Thread.sleep(interval);
            }
        }
    }

    /**
     * 当前时钟（毫秒），子类可覆写用于测试
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    protected abstract void add(T item);


    record Entry<T>(Map<Integer, List<T>> itemsMap) {
    }
}
