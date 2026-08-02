package com.wly.job.server.client.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScheduleRecCallbackTest {

    @BeforeAll
    static void initTableInfo() {
        // 单测环境无 MyBatis-Plus 自动装配，需手动初始化实体 lambda 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.wly.job.server.dao.entity.Job.class);
    }

    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleRecCallback callback = new ScheduleRecCallback(recQueue, jobRep, tracker);

    @Test
    void onSuccessOfSingleRunMarksFinishedAndSuccess() {
        tracker.add(7L);
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, true);

        callback.onSuccess(ctx, "ok");

        assertFalse(tracker.contains(7L));
        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
    }

    @Test
    void onFailureRemovesInFlightAndMarksFail() {
        tracker.add(7L);
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, true);

        callback.onFailure(ctx, new RuntimeException("boom"));

        assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, false);

        callback.onSuccess(ctx, "ok");

        verify(jobRep, never()).update(any(), any());
    }
}
