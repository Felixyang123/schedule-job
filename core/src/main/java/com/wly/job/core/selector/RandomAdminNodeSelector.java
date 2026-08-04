package com.wly.job.core.selector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机 Admin 节点选择：每次心跳随机选一个 Admin 地址作为首选，故障时由调用方按序转移；
 * 适用于各 Admin 节点能力对等、无需钉住关系的场景。
 */
public class RandomAdminNodeSelector implements AdminNodeSelector {

    @Override
    public int select(int size, String key) {
        return ThreadLocalRandom.current().nextInt(size);
    }
}
