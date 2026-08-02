package com.wly.job.server.client;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScheduleJobClientTest {

    @BeforeAll
    static void initTableInfo() {
        // 单测环境无 MyBatis-Plus 自动装配，需手动初始化实体 lambda 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.wly.job.server.dao.entity.Job.class);
    }

    @Test
    void onFailureRemovesInFlightAndMarksFail() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();
        tracker.add(7L);

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, true);
        callable.onFailure(new RuntimeException("boom"));

        org.junit.jupiter.api.Assertions.assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
    }

    @Test
    void onSuccessOfSingleRunMarksFinished() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, true);
        callable.onSuccess("ok");

        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, false);
        callable.onSuccess("ok");

        verify(jobRep, never()).update(any(), any());
    }
}
