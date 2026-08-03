package com.wly.job.core.selector;

/**
 * Worker 侧 Admin 节点选择器（ADR-0004 决策 #11）：
 * 从地址列表中选择首选下标；故障转移由调用方按 (start+i)%N 顺序执行。
 * 约定：size 必须大于 0。
 */
public interface AdminNodeSelector {

    int select(int size, String key);
}
