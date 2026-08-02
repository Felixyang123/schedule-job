package com.wly.job.server.utils;

import com.wly.job.common.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CronUtils {
    /**
     * 调度中心默认时区（与部署环境保持一致，可通过配置项扩展）
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static long getNextExecutionSecond(String cron) {
        LocalDateTime next = getNextExecution(cron);
        return next.atZone(ZONE).toEpochSecond();
    }

    public static long getNextExecutionMillis(String cron) {
        LocalDateTime next = getNextExecution(cron);
        return next.atZone(ZONE).toInstant().toEpochMilli();
    }

    public static long getNextExecutionNanos(String cron) {
        LocalDateTime next = getNextExecution(cron);
        return next.atZone(ZONE).toEpochSecond() * 1_000_000_000L + next.getNano();
    }

    /**
     * 计算下次执行时间
     */
    public static LocalDateTime getNextExecution(String cron) {
        return getNextExecution(cron, LocalDateTime.now());
    }

    /**
     * 计算从指定时间开始的下次执行时间（单次解析，非法 cron 包装为 ScheduleException）
     */
    public static LocalDateTime getNextExecution(String cron, LocalDateTime baseTime) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime next = expression.next(baseTime);
            if (next == null) {
                throw new ScheduleException("No next execution time found for cron: " + cron);
            }
            return next;
        } catch (IllegalArgumentException e) {
            throw new ScheduleException("CronExpression parse fail: " + cron, e);
        }
    }

    /**
     * 计算未来 N 次执行时间
     */
    public static List<LocalDateTime> getNextExecutions(String cron, int count) {
        return getNextExecutions(cron, LocalDateTime.now(), count);
    }

    public static List<LocalDateTime> getNextExecutions(String cron, LocalDateTime baseTime, int count) {
        checkCronExpression(cron);
        List<LocalDateTime> executions = new ArrayList<>();
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime next = baseTime;

        for (int i = 0; i < count; i++) {
            next = expression.next(next);
            if (next == null) {
                break;
            }
            executions.add(next);
        }

        return executions;
    }

    /**
     * 验证 Cron 表达式是否有效
     */
    public static void checkCronExpression(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.error("CronExpression: 【{}】 parse fail: ", cron, e);
            throw new ScheduleException("CronExpression parse fail");
        }
    }
}
