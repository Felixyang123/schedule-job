package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实例注册中心类型枚举（schedule.registry 配置值）。
 *
 * <p>DEFAULT 使用本地实例存储（DefaultInstanceRegistry），CENTER 接入第三方注册中心
 * （RemoteRegisterCenterRegistry，跨机房间享执行器实例）。
 */
@AllArgsConstructor
@Getter
public enum RegistryTypeEnum {
    /**
     * 默认（本地存储）
     */
    DEFAULT,
    /**
     * 第三方注册中心
     */
    CENTER
}
