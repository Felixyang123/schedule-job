package com.wly.job.server.stroage;

/**
 * 缓存型实例存储扩展接口：在 {@link Storage} 基础上增加过期元素清理能力。
 *
 * <p>用于基于 TTL / 心跳过期的缓存实现（如本地缓存），由生命周期管理触发周期性清理，
 * 防止失活实例长期占用内存。
 *
 * @param <T> 存储元素类型
 */
public interface CacheStorage<T> extends Storage<T> {

    /** 清理所有已过期的元素 */
    void clearExpired();
}
