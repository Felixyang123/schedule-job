package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;

/**
 * 调度作业服务：承接执行器注册与手动触发两类入口。
 *
 * <p>职责：
 * <ul>
 *   <li><b>注册</b>：执行器通过开放接口注册作业元数据（HTTP /open/job/register）与实例心跳
 *       （HTTP /open/job/instance/register），本服务将作业写入 {@code job} 表并埋注册变更记录
 *       （changeType=1），实例活性写入注册中心（Registry）。</li>
 *   <li><b>调度</b>：将一次执行包装为调度记录（RUNNING 起始态）后委托 {@link ScheduleService}
 *       选择执行器并派发；同步异常时把调度记录标记为失败并向上抛出。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduleJobService {

    private final Registry registry;

    private final JobRep jobRep;

    private final ScheduleRecQueue recQueue;

    private final ScheduleService scheduleService;

    private final JobChangeRep changeRep;

    /**
     * 注册作业元数据（执行器启动 / 心跳续约场景调用）。
     *
     * <p>事务边界：Job 行插入与注册变更记录（changeType=1）同事务写入。作业名由
     * {@code group:name} 唯一键约束，重复注册时静默忽略（视为幂等成功）。实例先注册到
     * Registry，即使作业已存在也保持实例在线。
     *
     * @param jobInfo 执行器携带的作业元数据与实例信息
     */
    @Transactional
    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null || jobInfo.getInstance() == null) {
            throw new ScheduleException("JobInfo and instance must not be null");
        }
        if (!StringUtils.hasText(jobInfo.getJobname()) || !StringUtils.hasText(jobInfo.getCron())) {
            throw new ScheduleException("Job name and cron must not be blank");
        }
        CronUtils.checkCronExpression(jobInfo.getCron());

        registry.register(jobInfo.getInstance());
        Job job = JobBeanConverter.convert(jobInfo).init();
        job.setCreator("system");
        job.setUpdater("system");
        try {
            jobRep.save(job);
        } catch (DuplicateKeyException exception) {
            log.debug("job already exists, register skip, job: {}",
                    job.getGroupName() + ":" + job.getName());
            return;
        }
        changeRep.record(job.getId(), JobChangeTypeEnum.REGISTER.getCode(),
                "system", null, job.getName());
        log.info("job registered, job: {}", job.getGroupName() + ":" + job.getName());
    }

    /**
     * 注册 / 刷新执行器实例心跳（HTTP /open/job/instance/register）。
     *
     * @param instance 执行器实例信息（含 IP、Netty 端口、心跳时间）
     */
    public void registerInstance(JobInstance instance) {
        registry.register(instance);
    }

    /**
     * 调度一次作业执行（手动触发与主节点派发共用入口）。
     *
     * <p>流程：先构造 RUNNING 态调度记录异步攒批落库，再委托 {@link ScheduleService#schedule}
     * 选择执行器并派发；若同步阶段抛异常（如无可用执行器、网络异常），将调度记录标记为 FAIL
     * 后向上抛出，交由调用方决定是否重试。
     *
     * @param job 待执行的作业元数据
     * @return 本次调度生成的 requestId（调用方可用于日志链路，MDC 在本方法返回/抛出时已清理）
     */
    public String schedule(Job job) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        // 派发线程/HTTP 线程常驻不经过 RequestLogFilter，MDC 恒为空；此处写入 requestId，
        // 使本方法内（选实例、RPC 派发、失败重试）日志携带与 schedule_rec 一致的链路 ID
        // （Spec 2026-08-06 §2.3，对应 JobScheduler worker 装饰捕获快照后任务中途写入的场景）。
        MDC.put("requestId", requestId);
        try {
            ScheduleRec scheduleRec = ScheduleRec.builder()
                    .jobId(job.getId())
                    .requestId(requestId)
                    .executeParam(job.getExecuteParam())
                    .scheduleTime(new Date())
                    .status(ScheduleRec.RUNNING)
                    .operator(UserSessionContext.getUserName())
                    .build();
            recQueue.save(scheduleRec);
            scheduleService.schedule(requestId, job);
            return requestId;
        } catch (Exception e) {
            recQueue.markFail(requestId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("requestId");
        }
    }
}
