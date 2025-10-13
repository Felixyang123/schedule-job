package com.wly.job.server.client.lb;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class InstanceSelectorFactory {
    private final List<InstanceSelector> selectors;

    public InstanceSelectorFactory(List<InstanceSelector> selectors) {
        this.selectors = Optional.ofNullable(selectors).orElse(new ArrayList<>());
    }

    public void addSelector(InstanceSelector selector) {
        this.selectors.add(selector);
    }

    public InstanceSelector select(Integer strategy) {
        return selectors.stream().filter(selector -> Objects.equals(strategy, selector.strategy())).findFirst().orElse(null);
    }
}
