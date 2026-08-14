package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.mapper.ScheduleRecMapper;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduleRecCleaner} 保留策略单测（Spec §2.10 / 验收标准 10）：
 * 只删除超过保留期的终态记录（FAIL/SUCCESS），RUNNING 永不删除。
 *
 * <p>清扫已改为**分批删除**：循环调用
 * {@link ScheduleRecMapper#deleteExpiredTerminalBatch(Date, int)}，直到某批返回行数
 * 小于 {@link ScheduleRecCleaner#DELETE_BATCH_SIZE}，或达到单轮批数上限
 * {@link ScheduleRecCleaner#MAX_BATCHES_PER_SWEEP}。因此本测试：
 * <ul>
 *   <li>通过包私有 {@code sweep()} 直接驱动，捕获 cutoff / batchSize 参数断言保留期计算；</li>
 *   <li>「只删终态、RUNNING 永不删除」的条件已下沉到 Mapper 的 {@code @Delete} 常量 SQL，
 *       故改为反射读取注解 SQL 文本断言该不变量；</li>
 *   <li>验证单轮批数上限生效（始终满批时不会无限循环）；</li>
 *   <li>验证首次清扫不在 {@code start()} 同步路径上执行（不阻塞 Spring 启动）。</li>
 * </ul>
 */
class ScheduleRecCleanerTest {

    private final ScheduleRecRep recRep = mock(ScheduleRecRep.class);

    private final ScheduleRecMapper mapper = mock(ScheduleRecMapper.class);

    @BeforeEach
    void setUp() {
        when(recRep.getBaseMapper()).thenReturn(mapper);
    }

    private ScheduleRecCleaner cleaner(int retentionDays) {
        return cleaner(retentionDays, ScheduleRecCleaner.DEFAULT_INITIAL_SWEEP_DELAY_MINUTES);
    }

    private ScheduleRecCleaner cleaner(int retentionDays, long initialSweepDelayMinutes) {
        ScheduleProps props = new ScheduleProps();
        props.setRecRetentionDays(retentionDays);
        // 测试实例独享 0ms 批间停顿，不再修改生产类静态状态污染其它用例。
        return new ScheduleRecCleaner(recRep, props, 0L, initialSweepDelayMinutes);
    }

    /** 读取分批删除方法上的 @Delete 常量 SQL，用于断言删除条件不变量 */
    private static String batchDeleteSql() throws NoSuchMethodException {
        Delete delete = ScheduleRecMapper.class
                .getMethod("deleteExpiredTerminalBatch", Date.class, int.class)
                .getAnnotation(Delete.class);
        assertNotNull(delete, "deleteExpiredTerminalBatch 必须由 @Delete 常量 SQL 定义删除条件");
        return delete.value()[0];
    }

    @Test
    void recRetentionDaysDefaultsTo7() {
        ScheduleProps props = new ScheduleProps();
        assertEquals(7, props.getRecRetentionDays(), "schedule.rec-retention-days 默认应为 7");
    }

    @Test
    void sweepDeletesOnlyTerminalRecordsBeforeCutoff() throws NoSuchMethodException {
        // 返回 0（不满批）表示一批就清完，循环立即终止
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt())).thenReturn(0);

        cleaner(7).sweep();

        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Integer> batchCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).deleteExpiredTerminalBatch(cutoffCaptor.capture(), batchCaptor.capture());

        // complete_time < now - retention（7 天），允许秒级时间容差
        Date cutoff = cutoffCaptor.getValue();
        assertNotNull(cutoff);
        long expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        assertTrue(Math.abs(cutoff.getTime() - expected) < 5_000L,
                "complete_time 截止点应约等于 now - 7 天，实际差 " + (cutoff.getTime() - expected) + "ms");
        assertEquals(ScheduleRecCleaner.DELETE_BATCH_SIZE, batchCaptor.getValue(),
                "每批删除行数应为 DELETE_BATCH_SIZE");

        // 删除条件下沉到常量 SQL：只含终态 FAIL(-1)/SUCCESS(1)，RUNNING(0) 永不删除
        String sql = batchDeleteSql();
        assertTrue(sql.contains("status IN (-1, 1)"),
                "删除条件必须只含终态 status IN (-1, 1)，实际: " + sql);
        assertTrue(sql.contains("complete_time <"),
                "删除条件必须含 complete_time < cutoff，实际: " + sql);
        assertTrue(sql.contains("LIMIT"), "必须按主键分批 LIMIT，实际: " + sql);
    }

    @Test
    void sweepAppliesConfiguredRetentionDays() {
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt())).thenReturn(0);

        cleaner(3).sweep();

        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        verify(mapper).deleteExpiredTerminalBatch(cutoffCaptor.capture(), anyInt());
        long expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3);
        Date cutoff = cutoffCaptor.getValue();
        assertTrue(Math.abs(cutoff.getTime() - expected) < 5_000L,
                "应使用配置的保留天数 3 计算截止点，实际差 " + (cutoff.getTime() - expected) + "ms");
    }

    @Test
    void sweepLoopsUntilBatchNotFull() {
        // 第一批满批（应继续），第二批不满批（应停止）
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt()))
                .thenReturn(ScheduleRecCleaner.DELETE_BATCH_SIZE)
                .thenReturn(7);

        cleaner(7).sweep();

        verify(mapper, times(2)).deleteExpiredTerminalBatch(any(Date.class), anyInt());
    }

    @Test
    void sweepStopsAtMaxBatchesPerSweep() {
        // 始终满批：必须被单轮批数上限截断，不能无限循环压满 DB
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt()))
                .thenReturn(ScheduleRecCleaner.DELETE_BATCH_SIZE);

        cleaner(7).sweep();

        verify(mapper, times(ScheduleRecCleaner.MAX_BATCHES_PER_SWEEP))
                .deleteExpiredTerminalBatch(any(Date.class), anyInt());
    }

    @Test
    void lifecycleStartStopIsGraceful() {
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt())).thenReturn(0);
        ScheduleRecCleaner cleaner = cleaner(7);

        cleaner.start();
        assertTrue(cleaner.isRunning());
        // 生产默认首次清扫延迟 1min，不在 start() 同步路径执行。
        verify(mapper, never()).deleteExpiredTerminalBatch(any(Date.class), anyInt());

        cleaner.stop();
        assertFalse(cleaner.isRunning());
        verify(mapper, never()).deleteExpiredTerminalBatch(any(Date.class), anyInt());
    }

    @Test
    void firstSweepRunsAfterConfigurableInitialDelay() throws Exception {
        CountDownLatch swept = new CountDownLatch(1);
        when(mapper.deleteExpiredTerminalBatch(any(Date.class), anyInt())).thenAnswer(invocation -> {
            swept.countDown();
            return 0;
        });
        ScheduleRecCleaner cleaner = cleaner(7, 0L);

        try {
            cleaner.start();
            assertTrue(swept.await(5, TimeUnit.SECONDS), "首次延迟任务应实际触发清扫");
            verify(mapper, times(1)).deleteExpiredTerminalBatch(any(Date.class), anyInt());
        } finally {
            cleaner.stop();
        }
        assertFalse(cleaner.isRunning());
    }
}
