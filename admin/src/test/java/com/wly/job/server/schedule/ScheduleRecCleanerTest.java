package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.mapper.ScheduleRecMapper;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduleRecCleaner} 保留策略单测（Spec §2.10 / 验收标准 10）：
 * 只删除超过保留期的终态记录（FAIL/SUCCESS），RUNNING 永不删除。
 *
 * <p>通过包私有 {@code sweep()} 直接驱动，捕获 {@code getBaseMapper().delete} 的 Wrapper 参数断言删除条件。
 */
class ScheduleRecCleanerTest {

    @BeforeAll
    static void initTableInfo() {
        // 单测环境无 MyBatis-Plus 自动装配，需手动初始化实体 lambda 缓存（同 ScheduleRunRecoveryTest）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ScheduleRec.class);
    }

    private final ScheduleRecRep recRep = mock(ScheduleRecRep.class);

    private final ScheduleRecMapper mapper = mock(ScheduleRecMapper.class);

    @BeforeEach
    void stubBaseMapper() {
        when(recRep.getBaseMapper()).thenReturn(mapper);
    }

    private ScheduleRecCleaner cleaner(int retentionDays) {
        ScheduleProps props = new ScheduleProps();
        props.setRecRetentionDays(retentionDays);
        return new ScheduleRecCleaner(recRep, props);
    }

    @Test
    void recRetentionDaysDefaultsTo7() {
        ScheduleProps props = new ScheduleProps();
        assertEquals(7, props.getRecRetentionDays(), "schedule.rec-retention-days 默认应为 7");
    }

    @Test
    void sweepDeletesOnlyTerminalRecordsBeforeCutoff() {
        cleaner(7).sweep();

        ArgumentCaptor<AbstractWrapper<ScheduleRec, ?, ?>> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).delete(captor.capture());
        AbstractWrapper<ScheduleRec, ?, ?> wrapper = captor.getValue();
        assertNotNull(wrapper);

        // SQL 片段应形如：status IN (?,?) AND complete_time < ?
        // 注意：MP 的 ISqlSegment 为惰性求值，先 getSqlSegment() 触发表达式求值并填充参数，再读参数
        String sql = wrapper.getSqlSegment();
        assertNotNull(sql);
        assertTrue(sql.contains("status IN"), "SQL 片段应含 status IN，实际: " + sql);
        assertTrue(sql.contains("complete_time"), "SQL 片段应含 complete_time，实际: " + sql);
        assertTrue(sql.contains("<"), "SQL 片段应含 < 截止条件，实际: " + sql);

        Map<String, Object> params = wrapper.getParamNameValuePairs();
        // 删除条件只含终态 FAIL/SUCCESS
        assertTrue(params.containsValue(ScheduleRec.FAIL), "删除条件必须包含 FAIL(-1)");
        assertTrue(params.containsValue(ScheduleRec.SUCCESS), "删除条件必须包含 SUCCESS(1)");
        // RUNNING(0) 绝不参与删除条件
        assertFalse(params.containsValue(ScheduleRec.RUNNING), "RUNNING(0) 永不删除");

        // complete_time < now - retention（7 天），允许秒级时间容差
        Date cutoff = params.values().stream()
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .findFirst()
                .orElseThrow();
        long expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        assertTrue(Math.abs(cutoff.getTime() - expected) < 5_000L,
                "complete_time 截止点应约等于 now - 7 天，实际差 " + (cutoff.getTime() - expected) + "ms");
    }

    @Test
    void sweepAppliesConfiguredRetentionDays() {
        cleaner(3).sweep();

        ArgumentCaptor<AbstractWrapper<ScheduleRec, ?, ?>> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).delete(captor.capture());
        AbstractWrapper<ScheduleRec, ?, ?> wrapper = captor.getValue();
        // 先触发表达式求值（惰性 ISqlSegment），再读参数
        wrapper.getSqlSegment();
        Date cutoff = wrapper.getParamNameValuePairs().values().stream()
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .findFirst()
                .orElseThrow();
        long expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3);
        assertTrue(Math.abs(cutoff.getTime() - expected) < 5_000L,
                "应使用配置的保留天数 3 计算截止点，实际差 " + (cutoff.getTime() - expected) + "ms");
    }

    @Test
    void lifecycleStartStopIsGraceful() {
        ScheduleRecCleaner cleaner = cleaner(7);

        cleaner.start();
        assertTrue(cleaner.isRunning());
        // 启动时先同步执行一次清扫（消除重启前已超期存量），随后进入 24h 固定周期
        verify(mapper, times(1)).delete(any());

        cleaner.stop();
        assertFalse(cleaner.isRunning());
        // 停止后不再触发额外清扫
        verify(mapper, times(1)).delete(any());
    }
}
