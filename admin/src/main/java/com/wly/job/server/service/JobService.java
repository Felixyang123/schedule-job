package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.UUID;

/**
 * 作业管理服务（管控后台写路径）。
 *
 * <p>负责作业元数据的编辑 / 启停 / 删除 / 分页查询与手动执行。所有写操作遵循 ADR-0005 变更源约定：
 * 编辑、启停、删除均在与 Job 行写入相同的数据库事务内追加一条 {@code job_change} 变更记录，
 * 由主节点消费变更源做增量对账；删除采用逻辑删除（deleted=1），语义为"不打断在途执行、
 * Worker 重新注册不复活任务"。
 */
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final JobChangeRep changeRep;

    /**
     * 编辑作业元数据。
     *
     * <p>事务边界：updateById 与编辑变更记录（changeType=2）同事务写入。若作业由单次任务改为普通任务，
     * 必须同事务将 finished 重置为 0，以维护 Finished 不变量（finished=1 ⇒ type=1）。
     *
     * @param req 编辑请求，仅携带需要更新的非空字段
     */
    @Transactional
    public void edit(EditJobReq req) {
        if (StringUtils.hasText(req.getCron())) {
            CronUtils.checkCronExpression(req.getCron());
        }
        Job current = jobRep.getById(req.getId());
        if (current == null) {
            throw new ScheduleException("任务不存在");
        }
        Job update = JobBeanConverter.convert(req);
        // 单次任务改为普通任务时，同事务重置单次任务的业务终态标记
        if (req.getType() != null
                && req.getType() == JobTypeEnum.GENERAL.getCode()
                && Objects.equals(current.getType(), JobTypeEnum.SINGLE.getCode())) {
            update.setFinished(0);
        }
        jobRep.updateById(update);
        changeRep.record(update.getId(), JobChangeTypeEnum.EDIT.getCode(),
                UserSessionContext.getUserName(), null, current.getName());
    }

    /**
     * 启停作业（状态在 0 停止 / 1 运行 间切换）。
     *
     * <p>事务边界：状态更新与启停变更记录（changeType=3）同事务写入。主节点消费变更源后将按
     * 当前行状态入队或摘除队列条目。
     *
     * @param id 作业 ID
     */
    @Transactional
    public void switchStatus(Long id) {
        Job job = jobRep.getById(id);
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }
        job.setStatus(Objects.equals(job.getStatus(), Job.ENABLE) ? Job.UNABLE : Job.ENABLE);
        jobRep.updateById(job);
        changeRep.record(job.getId(), JobChangeTypeEnum.SWITCH.getCode(),
                UserSessionContext.getUserName(), null, job.getName());
    }

    /**
     * 删除作业（逻辑删除）。
     *
     * <p>先写删除变更记录（changeType=5）再执行 removeById，两者同事务；框架会将 deleted 置为 1 而非物理删除。
     * 主节点消费变更源后移除队列条目，已派发到执行器的在途任务不被打断。
     *
     * @param id 作业 ID
     */
    @Transactional
    public void delete(Long id) {
        Job job = jobRep.getById(id);
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }
        changeRep.record(job.getId(), JobChangeTypeEnum.DELETE.getCode(),
                UserSessionContext.getUserName(), null, job.getName());
        jobRep.removeById(id);
    }

    /**
     * 分页查询作业（按 ID 倒序，支持作业分组名 / 作业名前缀模糊匹配）。
     *
     * @param pageReq 分页请求与查询条件
     * @return 作业分页结果
     */
    public PageResp<JobResp> page(PageReq<QueryJobReq> pageReq) {
        LambdaQueryWrapper<Job> wrapper = Wrappers.<Job>lambdaQuery().orderByDesc(Job::getId);
        if (pageReq.getQuery() != null) {
            wrapper.likeRight(StringUtils.hasText(pageReq.getQuery().getGroupName()), Job::getGroupName, pageReq.getQuery().getGroupName())
                    .likeRight(StringUtils.hasText(pageReq.getQuery().getJobname()), Job::getName, pageReq.getQuery().getJobname());
        }
        Page<Job> page = jobRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);
        return PageResp.of(page.convert(JobBeanConverter::convert).getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
    }

    /**
     * 手动执行一次作业（管控后台"执行"入口）。
     *
     * <p>仅允许运行中的作业触发，可临时覆盖执行参数（不持久化到 job 表）；实际派发复用
     * {@link ScheduleJobService#schedule(Job)}，走标准调度链路：落调度记录（RUNNING）→ 选择执行器 → Netty 派发。
     *
     * @param req 执行请求
     */
    public void exec(ExecJobReq req) {
        Job job = jobRep.getById(req.getJobId());
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }

        if (!job.isEnable()) {
            throw new ScheduleException("任务已禁用");
        }

        if (StringUtils.hasText(req.getExecuteParam())) {
            job.setExecuteParam(req.getExecuteParam());
        }

        // 手动调度任务入口注入 requestId（R2）：traceId 已由 RequestLogFilter 注入（R1，HTTP 链路贯穿），
        // requestId 每次执行独立，schedule_rec 用其作唯一关联键（Spec 2026-08-06 §2.3 分层模型）
        MDC.put("requestId", UUID.randomUUID().toString().replace("-", ""));
        try {
            scheduleJobService.schedule(job);
        } finally {
            MDC.remove("requestId");
        }
    }
}
