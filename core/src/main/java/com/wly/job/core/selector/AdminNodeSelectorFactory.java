package com.wly.job.core.selector;

/**
 * 按配置名创建选择器：ROUND_ROBIN（默认）/ RANDOM / HASH。
 */
public final class AdminNodeSelectorFactory {

    private AdminNodeSelectorFactory() {
    }

    public static AdminNodeSelector create(String name) {
        String type = name == null ? "" : name.trim().toUpperCase();
        return switch (type) {
            case "RANDOM" -> new RandomAdminNodeSelector();
            case "HASH" -> new HashAdminNodeSelector();
            default -> new RoundRobinAdminNodeSelector();
        };
    }
}
