package com.wly.job.server.client.lb;

import com.wly.job.common.bean.JobInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoundRobinSelectorTest {

    private final RoundRobinSelector selector = new RoundRobinSelector();

    @Test
    void emptyInstancesReturnsNull() {
        assertNull(selector.select(List.of()));
    }

    @Test
    void selectsInstancesInRoundRobinOrder() {
        List<JobInstance> instances = List.of(
                instance("group-a", "10.0.0.1"),
                instance("group-a", "10.0.0.2"));

        assertEquals("10.0.0.1", selector.select(instances).getHost());
        assertEquals("10.0.0.2", selector.select(instances).getHost());
        assertEquals("10.0.0.1", selector.select(instances).getHost());
        assertEquals("10.0.0.2", selector.select(instances).getHost());
    }

    @Test
    void countersAreIsolatedPerDiscoveryKey() {
        List<JobInstance> groupA = List.of(
                instance("group-a", "10.0.0.1"),
                instance("group-a", "10.0.0.2"));
        List<JobInstance> groupB = List.of(
                instance("group-b", "10.0.0.3"),
                instance("group-b", "10.0.0.4"));

        // group-a 轮询一轮后，group-b 应从自己的 0 开始，而不是共享全局计数
        selector.select(groupA);
        selector.select(groupA);

        assertEquals("10.0.0.3", selector.select(groupB).getHost());
        assertEquals("10.0.0.4", selector.select(groupB).getHost());
    }

    private static JobInstance instance(String discoveryKey, String host) {
        return JobInstance.builder().discoveryKey(discoveryKey).host(host).port(8101).build();
    }
}
