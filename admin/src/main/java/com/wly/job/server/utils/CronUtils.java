package com.wly.job.server.utils;

import com.wly.job.common.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CronUtils {
    public static long getNextExecutionSecond(String cron) {
        LocalDateTime next = getNextExecution(cron);
        return next.toEpochSecond(ZoneOffset.of("+8"));
    }

    /**
     * 计算下次执行时间
     */
    public static LocalDateTime getNextExecution(String cron) {
        return getNextExecution(cron, LocalDateTime.now());
    }

    /**
     * 计算从指定时间开始的下次执行时间
     */
    public static LocalDateTime getNextExecution(String cron, LocalDateTime baseTime) {
        checkCronExpression(cron);
        CronExpression expression = CronExpression.parse(cron);
        return expression.next(baseTime);
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