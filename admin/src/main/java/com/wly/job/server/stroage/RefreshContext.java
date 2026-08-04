package com.wly.job.server.stroage;

import lombok.Data;

import java.util.List;

/**
 * 刷新上下文（RefreshContext）：承载后台刷新线程一次拉取周期内的游标与待回灌数据。
 *
 * <p>{@link RefreshStorage#newDataCollection} 以本对象携带游标增量拉取新数据并写入
 * {@link #newDataCollection}，{@link RefreshStorage#refresh} 消费该集合回灌缓存。
 *
 * @param <T> 存储元素类型
 */
@Data
public class RefreshContext<T> {

    /**
     * 查询游标：上次已处理数据的主键 ID，用于增量拉取（0 表示从头开始）
     */
    private long cursor;

    /** 本周期新拉取的数据集合（供 refresh 回灌缓存） */
    private List<T> newDataCollection;
}
