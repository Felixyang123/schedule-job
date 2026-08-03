package com.wly.job.core.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminNodeSelectorTest {

    @Test
    void roundRobinCyclesAndWraps() {
        AdminNodeSelector selector = new RoundRobinAdminNodeSelector();
        assertEquals(0, selector.select(3, "k"));
        assertEquals(1, selector.select(3, "k"));
        assertEquals(2, selector.select(3, "k"));
        assertEquals(0, selector.select(3, "k"));
    }

    @Test
    void randomStaysInRange() {
        AdminNodeSelector selector = new RandomAdminNodeSelector();
        for (int i = 0; i < 100; i++) {
            int index = selector.select(3, "k");
            assertTrue(index >= 0 && index < 3);
        }
    }

    @Test
    void hashIsStablePerKey() {
        AdminNodeSelector selector = new HashAdminNodeSelector();
        assertEquals(selector.select(4, "job:host:8101"), selector.select(4, "job:host:8101"));
        assertEquals(selector.select(4, "other:host:8101"), selector.select(4, "other:host:8101"));
    }

    @Test
    void factoryDefaultsToRoundRobin() {
        assertInstanceOf(RoundRobinAdminNodeSelector.class, AdminNodeSelectorFactory.create(null));
        assertInstanceOf(RoundRobinAdminNodeSelector.class, AdminNodeSelectorFactory.create("round_robin"));
        assertInstanceOf(RandomAdminNodeSelector.class, AdminNodeSelectorFactory.create("RANDOM"));
        assertInstanceOf(HashAdminNodeSelector.class, AdminNodeSelectorFactory.create("hash"));
    }
}
