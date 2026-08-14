package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.mapper.JobMapper;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleJobServiceTest {

    private final Registry registry = mock(Registry.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final ScheduleJobService service =
            new ScheduleJobService(registry, jobRep, recQueue, scheduleService, changeRep);

    @BeforeEach
    void setUp() {
        when(jobRep.getBaseMapper()).thenReturn(jobMapper);
    }

    private static JobInfo info() {
        return JobInfo.builder()
                .jobname("j").group("g").cron("0/5 * * * * ?")
                .build();
    }

    @Test
    void firstRegisterRecordsRegisterChange() {
        when(jobMapper.insertIfAbsent(any(Job.class))).thenReturn(1);

        service.registerJob(info());

        verify(changeRep).record(any(), eq(JobChangeTypeEnum.REGISTER.getCode()),
                eq("system"), isNull(), eq("j"));
    }

    @Test
    void existingJobIsSkippedWithoutException() {
        // 条件插入命中已存在：返回 0 行，正常重复注册不再走异常路径
        when(jobMapper.insertIfAbsent(any(Job.class))).thenReturn(0);

        service.registerJob(info());

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentInsertRaceIsSwallowed() {
        // 两个 Worker 同时穿透 NOT EXISTS：由唯一键裁决，落败方视为已存在
        when(jobMapper.insertIfAbsent(any(Job.class))).thenThrow(new DuplicateKeyException("dup"));

        service.registerJob(info());

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void nonUniqueKeyViolationIsPropagated() {
        // 字段超长等数据问题必须抛出，不得被误判为「作业已存在」
        when(jobMapper.insertIfAbsent(any(Job.class)))
                .thenThrow(new DataIntegrityViolationException("Data too long for column 'name'"));

        assertThrows(DataIntegrityViolationException.class, () -> service.registerJob(info()));

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void registerJobDoesNotRegisterInstance() {
        // 注册解耦：作业注册不再有实例注册副作用（Spec 2026-08-12）
        when(jobMapper.insertIfAbsent(any(Job.class))).thenReturn(1);

        service.registerJob(info());

        verify(registry, never()).register(any());
    }

    @Test
    void nullJobInfoIsRejected() {
        assertThrows(ScheduleException.class, () -> service.registerJob(null));
    }
}
