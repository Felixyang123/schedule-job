package com.wly.job.server.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronUtilsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void nextExecutionNanosMatchesLocalDateTimePrecisely() {
        String cron = "0/5 * * * * ?";
        LocalDateTime next = CronUtils.getNextExecution(cron);
        long expected = next.atZone(ZONE).toEpochSecond() * 1_000_000_000L + next.getNano();

        long actual = CronUtils.getNextExecutionNanos(cron);

        assertEquals(expected, actual);
        assertTrue(actual >= System.currentTimeMillis() * 1_000_000L);
    }

    @Test
    void nextExecutionMillisMatchesEpochMillis() {
        String cron = "0/5 * * * * ?";
        LocalDateTime next = CronUtils.getNextExecution(cron);

        assertEquals(next.atZone(ZONE).toInstant().toEpochMilli(), CronUtils.getNextExecutionMillis(cron));
    }

    @Test
    void invalidCronThrowsScheduleException() {
        assertThrows(com.wly.job.common.exception.ScheduleException.class,
                () -> CronUtils.getNextExecution("not-a-cron"));
    }
}
