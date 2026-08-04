package com.wly.job.server.stroage;

import java.util.Collection;
import java.util.List;

/**
 * 集群实例存储抽象：定义执行器实例的后端存取能力。
 *
 * <p>支撑调度中心的多种实例注册与存储方式（ADR-0002 的多存储抽象），不同实现对应不同后端：
 * <ul>
 *   <li>{@link LocalCacheJobInstanceStorage}：进程内 ConcurrentMap 本地缓存。</li>
 *   <li>{@link RedisJobInstanceStorage}：Redis 字符串 + Set 存储（带 TTL 自动过期）。</li>
 *   <li>{@link JobInstancePersistStorage}：instance 物理表持久化。</li>
 *   <li>{@link RefreshJobInstanceStorage}：持久化 + 缓存两级存储，后台线程定期刷缓存。</li>
 * </ul>
 * <p>语义约定：{@link #put} 为幂等"存在则更新、不存在则新增"，{@link #add} 为"仅新增"；
 * {@link #list} 返回活跃（未过期 / 在线）的实例列表，供调度派发前发现候选执行器。
 *
 * @param <T> 存储元素类型（通常为 {@code JobInstance}）
 */
public interface Storage<T> {

    /** 按键获取单个元素（大部分实现不支持，抛 UnsupportedOperationException） */
    T get(String key);

    /** 幂等写入 / 更新元素（存在则更新，不存在则新增） */
    void put(T value);

    /** 批量幂等写入 / 更新元素 */
    void putAll(Collection<T> values);

    /** 移除元素（实现可按需置为下线或直接删除） */
    void remove(T value);

    /** 清空全部存储内容（主备切换清理等场景使用） */
    void clear();

    /** 仅新增元素（已存在则不覆盖） */
    void add(T value);

    /** 批量仅新增元素 */
    void addAll(Collection<T> values);

    /** 按发现键集合批量拉取活跃元素列表（供调度发现候选执行器） */
    List<T> list(Collection<String> keys);
}
