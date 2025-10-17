package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实例注册中心类型
 */
@AllArgsConstructor
@Getter
public enum RegistryTypeEnum {
    /**
     * 默认
     */
    DEFAULT,
    /**
     * 第三方注册中心
     */
    CENTER
}
