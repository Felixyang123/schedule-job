package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.utils.CronUtils;

import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public record ScheduleJob(Job job, long expireNanos) implements Delayed {
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expireNanos - getNanos(), TimeUnit.NANOSECONDS);
    }

    private long getNanos() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    @Override
    public int compareTo(Delayed o) {
        if (o == this) {
            return 0;
        }
        if (o instanceof ScheduleJob other) {
            long diff = expireNanos - other.expireNanos;
            if (diff < 0) {
                return -1;
            } else if (diff > 0) {
                return 1;
            } else {
                return 0;
            }
        }
        long d = (getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS));
        return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
    }

    public static ScheduleJob of(Job job) {
        return new ScheduleJob(job, CronUtils.getNextExecutionNanos(job.getCron()));
    }
}
