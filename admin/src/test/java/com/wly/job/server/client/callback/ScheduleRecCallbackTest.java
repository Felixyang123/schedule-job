package com.wly.job.server.client.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleRecCallbackTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.wly.job.server.dao.entity.Job.class);
    }

    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final ScheduleRecCallback callback = new ScheduleRecCallback(recQueue, jobRep, tracker, changeRep);

    private static ScheduleCallbackContext ctx(String requestId) {
        return new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId(requestId).jobname("once-7").build(), 7L, true);
    }

    @Test
    void onSuccessOfSingleRunMarksFinishedAndRecords() {
        tracker.add(7L);
        when(jobRep.update(isNull(), any())).thenReturn(true);

        callback.onSuccess(ctx("req-1"), "ok");

        assertFalse(tracker.contains(7L));
        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
        verify(changeRep).record(eq(7L), eq(JobChangeTypeEnum.FINISHED.getCode()),
                eq("system"), eq("req-1"), eq("once-7"));
    }

    @Test
    void onSuccessSkipsRecordWhenUpdateMissed() {
        when(jobRep.update(isNull(), any())).thenReturn(false);

        callback.onSuccess(ctx("req-1"), "ok");

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void onFailureRemovesInFlightMarksFailAndRecordsRequeue() {
        tracker.add(7L);

        callback.onFailure(ctx("req-1"), new RuntimeException("boom"));

        assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
        verify(changeRep).record(eq(7L), eq(JobChangeTypeEnum.REQUEUE.getCode()),
                eq("system"), eq("req-1"), eq("once-7"));
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleCallbackContext general = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, false);

        callback.onSuccess(general, "ok");

        verify(jobRep, never()).update(any(), any());
        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }
}
