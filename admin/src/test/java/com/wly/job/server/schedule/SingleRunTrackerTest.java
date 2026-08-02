package com.wly.job.server.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleRunTrackerTest {

    private final SingleRunTracker tracker = new SingleRunTracker();

    @Test
    void addContainsAndRemove() {
        assertFalse(tracker.contains(1L));
        tracker.add(1L);
        assertTrue(tracker.contains(1L));
        tracker.remove(1L);
        assertFalse(tracker.contains(1L));
    }

    @Test
    void nullJobIdIsIgnored() {
        tracker.add(null);
        assertFalse(tracker.contains(null));
        tracker.remove(null);
    }
}
