package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.utils.CronUtils;

import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 调度引擎队列元素：作业轻量投影 {@link JobView} + 下次执行时间（epoch 纳秒）。
 * 实现 {@link Delayed} 使 DelayQueue/时间轮可以按到期时间排序与取用；
 * {@code expireNanos} 由 {@link #of} 依据作业 Cron 计算得出，引擎只负责按时间触发，
 * 不关心到期后如何执行。
 */
public record ScheduleJob(JobView job, long expireNanos) implements Delayed {
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expireNanos - getNanos(), TimeUnit.NANOSECONDS);
    }

    /** 当前系统时间的纳秒表示（epoch 秒 × 10^9 + 纳秒偏移） */
    private long getNanos() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /**
     * 到期时间比较：先比较绝对到期纳秒，同值时退化为按延迟差值比较，
     * 保证同刻度多个任务的相对顺序稳定。
     */
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

    /**
     * 基于作业投影创建队列元素，下次执行时间由 Cron 表达式计算。
     */
    public static ScheduleJob of(JobView job) {
        return new ScheduleJob(job, CronUtils.getNextExecutionNanos(job.cron()));
    }
}
