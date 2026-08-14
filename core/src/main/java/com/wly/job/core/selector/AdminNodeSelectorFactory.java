package com.wly.job.core.selector;

/**
 * 按配置名创建选择器：ROUND_ROBIN（默认）/ RANDOM / HASH。
 */
public final class AdminNodeSelectorFactory {

    private AdminNodeSelectorFactory() {
    }

    /**
     * 每个 Worker 工厂创建独立选择器实例，避免 ROUND_ROBIN 游标等有状态数据跨应用共享。
     * 当前仅支持三种内建策略；扩展需同步配置枚举与测试，暂不引入 SPI 的发现/类加载复杂度。
     */
    public static AdminNodeSelector create(String name) {
        String type = name == null ? "" : name.trim().toUpperCase();
        return switch (type) {
            case "RANDOM" -> new RandomAdminNodeSelector();
            case "HASH" -> new HashAdminNodeSelector();
            default -> new RoundRobinAdminNodeSelector();
        };
    }
}
