package com.wly.job.server.stroage;

import java.util.concurrent.Executors;

public interface CacheStorage<T> extends Storage<T> {

    void clearExpired();

    default void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(this::clearExpired, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    default void stop() {
    }
}
