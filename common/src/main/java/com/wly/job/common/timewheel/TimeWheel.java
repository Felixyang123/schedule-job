package com.wly.job.common.timewheel;

import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public abstract class TimeWheel<T> {
    /**
     * 槽间隔（秒）
     */
    private final int tick;

    private final int wheelSize;

    private final List<Entry<T>> entries;

    private final long tickMs;

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


    public record Entry<T>(Map<Integer, List<T>> itemsMap) {
    }
}
