package com.wly.job.server.client.lb;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 实例选择器工厂：Spring 自动收集全部 {@link InstanceSelector} Bean，
 * 按策略码提供选择器查找；新增路由策略时只需实现 InstanceSelector 并以 Bean 注册即可。
 */
@Component
public class InstanceSelectorFactory {
    private final List<InstanceSelector> selectors;

    public InstanceSelectorFactory(List<InstanceSelector> selectors) {
        this.selectors = Optional.ofNullable(selectors).orElse(new ArrayList<>());
    }

    public InstanceSelector select(Integer strategy) {
        return selectors.stream().filter(selector -> Objects.equals(strategy, selector.strategy())).findFirst().orElse(null);
    }
}
