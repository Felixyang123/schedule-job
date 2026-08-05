package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.schedule.ScheduleRecQueue.SaveTask;
import com.wly.job.server.schedule.ScheduleRecQueue.UpdateTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduleRecQueue} 落库可靠性单测（Spec §2.5 / 验收标准 5）：
 * 失败退避重试不丢批、重试耗尽仅记日志并清批次、队列打满丢弃不阻塞。
 *
 * <p>通过包私有 {@code flush} 与定容队列构造直接驱动，避免消费线程时序。
 */
class ScheduleRecQueueTest {

    private final ScheduleRecRep recRep = mock(ScheduleRecRep.class);

    private static ScheduleRec rec(long jobId) {
        return ScheduleRec.builder().jobId(jobId).build();
    }

    // ---- (a) saveBatch 失败后退避重试，最终成功，批次不丢 ----

    @Test
    void saveBatchRetriesAndEventuallyPersists() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(10000));
        when(recRep.saveBatch(anyCollection()))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(true);

        List<SaveTask> saveBatch = new ArrayList<>(List.of(new SaveTask(rec(1L)), new SaveTask(rec(2L))));
        queue.flush(saveBatch, new ArrayList<>());

        // 首次 + 2 次重试成功，批次内记录完整且顺序不变
        ArgumentCaptor<List<ScheduleRec>> captor = ArgumentCaptor.forClass(List.class);
        verify(recRep, times(3)).saveBatch(captor.capture());
        List<ScheduleRec> persisted = captor.getAllValues().get(2);
        assertEquals(List.of(1L, 2L), persisted.stream().map(ScheduleRec::getJobId).toList());
        assertEquals(0, queue.saveFailCount());
        assertTrue(saveBatch.isEmpty());
    }

    // ---- (b) saveBatch 始终失败：重试 3 次耗尽后仅记日志 + 计数，批次清空继续循环 ----

    @Test
    void saveBatchRetriesExhaustedLogsAndClears() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(10000));
        when(recRep.saveBatch(anyCollection())).thenThrow(new RuntimeException("db down"));

        List<SaveTask> saveBatch = new ArrayList<>(List.of(new SaveTask(rec(1L))));
        queue.flush(saveBatch, new ArrayList<>());

        // 首次尝试 + 3 次重试 = 4 次调用，重试有上限不无限循环
        verify(recRep, times(4)).saveBatch(anyCollection());
        assertEquals(1, queue.saveFailCount());
        assertTrue(saveBatch.isEmpty());
    }

    // ---- (c) 队列打满：save/mark 丢弃新记录，不抛异常、不阻塞 ----

    @Test
    void saveDropsWhenQueueFull() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(1));
        queue.save(rec(1L)); // 占满容量

        assertDoesNotThrow(() -> queue.save(rec(2L))); // 打满丢弃，不阻塞不抛异常
        assertEquals(1, queue.droppedCount());
    }

    @Test
    void markUpdateDropsWhenQueueFull() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(1));
        queue.save(rec(1L)); // 占满容量

        assertDoesNotThrow(() -> queue.markSuccess("req-1", "ok"));
        assertEquals(1, queue.droppedCount());
    }

    // ---- update 逐行重试：失败行单独重试，不重放已成功行 ----

    @Test
    void updateRetriesOnlyFailingRowAndContinues() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(10000));
        when(recRep.update(any(), any()))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(true);

        List<UpdateTask> updateBatch = new ArrayList<>(List.of(
                new UpdateTask("req-1", ScheduleRec.SUCCESS, "ok"),
                new UpdateTask("req-2", ScheduleRec.FAIL, "boom")));
        queue.flush(new ArrayList<>(), updateBatch);

        // 第一条：初始失败 + 2 次重试成功 = 3 次；第二条：直接成功 = 1 次
        verify(recRep, times(4)).update(any(), any());
        assertEquals(0, queue.updateFailCount());
        assertTrue(updateBatch.isEmpty());
    }

    @Test
    void updateRetriesExhaustedSkipsRowAndContinues() {
        ScheduleRecQueue queue = new ScheduleRecQueue(recRep, new LinkedBlockingQueue<>(10000));
        when(recRep.update(any(), any()))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(true);

        List<UpdateTask> updateBatch = new ArrayList<>(List.of(
                new UpdateTask("req-1", ScheduleRec.SUCCESS, "ok"),
                new UpdateTask("req-2", ScheduleRec.FAIL, "boom")));
        queue.flush(new ArrayList<>(), updateBatch);

        // 第一条：初始 + 3 次重试耗尽 = 4 次失败；第二条：仍被处理成功（不中断后续行）
        verify(recRep, times(5)).update(any(), any());
        assertEquals(1, queue.updateFailCount());
        assertTrue(updateBatch.isEmpty());
    }
}
