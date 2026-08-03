package com.wly.job.core.selector;

/**
 * 按 key（实例键 discoveryKey:host:port）稳定钉住同一个 Admin；故障时由调用方循环转移。
 */
public class HashAdminNodeSelector implements AdminNodeSelector {

    @Override
    public int select(int size, String key) {
        return Math.floorMod(key == null ? 0 : key.hashCode(), size);
    }
}
