package com.wly.job.server.stroage;

import org.springframework.util.CollectionUtils;

import java.util.List;

public interface RefreshStorage<T> extends Storage<T> {
    default void start() {
        Thread refreshInstancesCacheThread = new Thread(() -> {
            while (running()) {
                RefreshContext<T> refreshContext = new RefreshContext<>();

                List<T> newDataCollection = newDataCollection(refreshContext);

                while (!CollectionUtils.isEmpty(newDataCollection)) {

                    refresh(refreshContext);

                    newDataCollection = newDataCollection(refreshContext);
                }

                try {
                    Thread.sleep(30 * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        refreshInstancesCacheThread.setName("refresh-instances-thread");
        refreshInstancesCacheThread.start();
    }

    default void stop() {
    }

    boolean running();

    List<T> newDataCollection(RefreshContext<T> refreshContext);

    void refresh(RefreshContext<T> refreshContext);
}
