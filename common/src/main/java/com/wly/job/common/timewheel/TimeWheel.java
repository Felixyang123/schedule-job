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
        this.startMs = System.currentTimeMillis();
        this.stop = false;
    }

    public void add(T item, long expire) {
        lock.lock();
        try {
            long seconds = (expire - startMs) / 1000;

            long ticks = seconds / tick;

            int index = (int) (ticks % wheelSize);

            int epoch = (int) ticks / wheelSize;

            Entry<T> entry = entries.get(index);

            if (entry == null) {
                entry = new Entry<>(new HashMap<>());
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

            return entry.itemsMap.remove(epoch);
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        this.stop = true;
    }

    @SneakyThrows
    public void clock(Function<List<T>, List<T>> task) {
        while (!stop) {
            long start = System.currentTimeMillis();
            List<T> items = getAndRemove(start);
            if (items != null) {
                List<T> apply = task.apply(items);
                if (apply != null) {
                    apply.forEach(this::add);
                }
            }
            long end = System.currentTimeMillis();
            long interval = tickMs - (end - start);
            if (interval > 0) {
                Thread.sleep(interval);
            }
        }
    }

    protected abstract void add(T item);


    public record Entry<T>(Map<Integer, List<T>> itemsMap) {
    }
}
