package com.wly.job.server.stroage;

import java.util.List;

/**
 * 可刷新存储扩展接口：在 {@link Storage} 基础上提供后台周期性回灌缓存的数据能力。
 *
 * <p>典型用法：持久化（DB）与缓存（本地/Redis）两级存储，后台线程每隔 30s 从持久层按游标
 * 增量拉取新数据并刷新缓存，使缓存接近持久层的最新状态（refresh-instances-thread）。
 *
 * <p>本接口只约定"取数 + 回灌"两个数据操作与 {@link #running} 运行标志；
 * <b>线程生命周期由实现类自行管理</b>（实现 SmartLifecycle 的类在 start/stop 中启动/终止线程），
 * 避免把线程管理放进接口默认方法导致职责错位与健壮性隐患（单次 DB 异常即线程永久死亡）。
 *
 * @param <T> 存储元素类型
 */
public interface RefreshStorage<T> extends Storage<T> {

    /** @return 刷新循环是否继续运行（实现类持有运行标志，停止时置 false） */
    boolean running();

    /** 按游标增量拉取一批新数据并写入 {@code refreshContext}（实现须推进游标） */
    List<T> newDataCollection(RefreshContext<T> refreshContext);

    /** 将本批新数据刷新到缓存（实现内执行 cacheStorage.put 等回灌动作） */
    void refresh(RefreshContext<T> refreshContext);
}
