package com.wly.job.server.stroage;

import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 可刷新存储扩展接口：在 {@link Storage} 基础上提供后台周期性回灌缓存的能力。
 *
 * <p>典型用法：持久化（DB）与缓存（本地/Redis）两级存储，后台线程每隔 30s 从持久层按游标
 * 增量拉取新数据并刷新缓存，使缓存接近持久层的最新状态（refresh-instances-thread）。
 *
 * <p>默认 {@link #start} 的循环语义：每次周期内反复调用 {@link #newDataCollection} 直到
 * 取空（游标推进），再 sleep 30s 进入下一周期。
 *
 * @param <T> 存储元素类型
 */
public interface RefreshStorage<T> extends Storage<T> {

    /** 启动后台刷新线程（循环拉取新数据并 refresh，见接口注释） */
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

    /** 停止刷新（由实现类自行置 running=false 并释放资源） */
    default void stop() {
    }

    /** @return 刷新线程是否继续运行 */
    boolean running();

    /** 按游标增量拉取一批新数据并写入 {@code refreshContext}（实现须推进游标） */
    List<T> newDataCollection(RefreshContext<T> refreshContext);

    /** 将本批新数据刷新到缓存（实现内执行 cacheStorage.put 等回灌动作） */
    void refresh(RefreshContext<T> refreshContext);
}
