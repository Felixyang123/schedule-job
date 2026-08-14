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
import org.springframework.transaction.annotation.Isolation;
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
 *       （HTTP /open/job/instance/register）。两者相互独立：前者只把作业写入 {@code job} 表并埋
 *       注册变更记录（changeType=1），后者只把实例活性写入注册中心（Registry）。</li>
 *   <li><b>调度</b>：将一次执行包装为调度记录（RUNNING 起始态）后委托 {@link ScheduleService}
 *       选择执行器并派发；同步异常时把调度记录标记为失败并向上抛出。</li>
 * </ul>
 *
 * <p>注册与派发保留在同一门面服务中，以维持现有公开调用面；内部依赖分别委托
 * {@link Registry}、{@link JobRep} 与 {@link ScheduleService}，方法级职责和事务边界独立。
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
     * 注册作业元数据（执行器启动时一次性调用）。
     *
     * <p>事务边界：Job 行插入与注册变更记录（changeType=1）同事务写入。
     *
     * <p>去重：使用条件插入（{@code INSERT ... WHERE NOT EXISTS}），重复注册返回 0 行属正常路径，
     * 不再以唯一键异常做流程控制；并发穿透时由唯一键裁决，落败方捕获后同样视为已存在。
     * 作业已存在时<b>不同步 Worker 上报的元数据</b>——作业创建后 Admin 管理端为权威配置源。
     *
     * <p>本方法<b>不注册实例</b>：实例注册与心跳续约走 {@link #registerInstance}
     * （Spec 2026-08-12 作业注册与实例注册解耦）。
     *
     * @param jobInfo 执行器携带的作业元数据
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null) {
            throw new ScheduleException("JobInfo must not be null");
        }
        if (!StringUtils.hasText(jobInfo.getJobname()) || !StringUtils.hasText(jobInfo.getCron())) {
            throw new ScheduleException("Job name and cron must not be blank");
        }
        CronUtils.checkCronExpression(jobInfo.getCron());

        Job job = JobBeanConverter.convert(jobInfo).init();
        int inserted;
        try {
            inserted = jobRep.getBaseMapper().insertIfAbsent(job);
        } catch (DuplicateKeyException exception) {
            // 并发穿透 NOT EXISTS 的罕见竞态：唯一键裁决，落败方视为已存在
            // （只捕获唯一键冲突；字段超长等数据问题必须向上抛出）
            log.debug("job insert lost race, register skip, job: {}",
                    job.getGroupName() + ":" + job.getName());
            return;
        }
        if (inserted == 0) {
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
     * <p>requestId 来源（Spec 2026-08-06 §2.3 分层模型）：
     * <ul>
     *   <li>手动触发（/admin/job/exec）：由 {@code RequestLogFilter} 注入的 HTTP 层 requestId（X-Request-Id）；</li>
     *   <li>主节点 cron 派发：由 {@code JobScheduler} 派发线程（任务入口）注入；</li>
     *   <li>接管补触发：由 {@code ScheduleRunRecovery} 注入。</li>
     * </ul>
     * 本方法<b>只从 MDC 读取</b>、不注入；仅当 MDC 为空（兜底防御）时生成一个仅用于 schedule_rec
     * 关联的 requestId（不注入 MDC）。
     *
     * <p>流程：先构造 RUNNING 态调度记录异步攒批落库，再委托 {@link ScheduleService#schedule}
     * 选择执行器并派发；若同步阶段抛异常（如无可用执行器、网络异常），将调度记录标记为 FAIL
     * 后向上抛出，交由调用方决定是否重试。
     *
     * @param job 待执行的作业元数据
     */
    public void schedule(Job job) {
        // 读取入口已注入的 requestId；为空（兜底防御）时生成一个仅用于 schedule_rec 关联，不注入 MDC
        // （注入职责统一在入口边界，见 Spec 2026-08-06 §2.3）。
        // schedule_rec.request_id 为 NOT NULL 且带唯一键，而落库走异步攒批（异常不回传调用方），
        // 缺兜底会让未注入 MDC 的调用路径静默丢调度记录，故此处必须保留。
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        ScheduleRec scheduleRec = ScheduleRec.builder()
                .jobId(job.getId())
                .requestId(requestId)
                .executeParam(job.getExecuteParam())
                .scheduleTime(new Date())
                .status(ScheduleRec.RUNNING)
                .operator(UserSessionContext.getUserName())
                .build();
        recQueue.save(scheduleRec);
        try {
            scheduleService.schedule(requestId, job);
        } catch (Exception e) {
            recQueue.markFail(requestId, e.getMessage());
            throw e;
        }
    }
}
