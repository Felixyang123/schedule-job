package com.wly.job.server.stroage;

public interface CacheStorage<T> extends Storage<T> {

    void clearExpired();
}
