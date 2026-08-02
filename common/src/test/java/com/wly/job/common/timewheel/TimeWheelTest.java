package com.wly.job.common.timewheel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWheelTest {

    private static final class StringWheel extends TimeWheel<String> {
        private long now;

        private StringWheel(int tick, int wheelSize) {
            super(tick, wheelSize);
        }

        @Override
        protected void add(String item) {
            throw new UnsupportedOperationException("not used in tests");
        }

        void setNow(long now) {
            this.now = now;
        }

        @Override
        protected long currentTimeMillis() {
            return now;
        }
    }

    @Test
    void addAndRemoveWithFutureExpire() {
        StringWheel wheel = new StringWheel(1, 60);
        long expire = System.currentTimeMillis() + 10_000;

        wheel.add("job-1", expire);
        assertTrue(wheel.remove("job-1", expire));
        assertNull(wheel.getAndRemove(expire));
    }

    @Test
    void overdueItemIsClampedToCurrentSlotInsteadOfCrashing() {
        StringWheel wheel = new StringWheel(1, 60);
        long startMs = System.currentTimeMillis();
        wheel.setNow(startMs + 10_000);
        long past = startMs + 5_000;

        // 过期任务不能产生负索引
        wheel.add("job-overdue", past);
        List<String> items = wheel.getAndRemove(startMs + 10_000);
        assertNotNull(items);
        assertEquals(List.of("job-overdue"), items);
    }

    @Test
    void removeReturnsFalseForUnknownItem() {
        StringWheel wheel = new StringWheel(1, 60);
        long expire = System.currentTimeMillis() + 5_000;

        assertFalse(wheel.remove("unknown", expire));
    }
}
