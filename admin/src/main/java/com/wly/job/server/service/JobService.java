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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final JobChangeRep changeRep;

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
        if (req.getType() != null
                && req.getType() == JobTypeEnum.GENERAL.getCode()
                && Objects.equals(current.getType(), JobTypeEnum.SINGLE.getCode())) {
            update.setFinished(0);
        }
        jobRep.updateById(update);
        changeRep.record(update.getId(), JobChangeTypeEnum.EDIT.getCode(),
                UserSessionContext.getUserName(), null, current.getName());
    }

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

    public PageResp<JobResp> page(PageReq<QueryJobReq> pageReq) {
        LambdaQueryWrapper<Job> wrapper = Wrappers.<Job>lambdaQuery().orderByDesc(Job::getId);
        if (pageReq.getQuery() != null) {
            wrapper.likeRight(StringUtils.hasText(pageReq.getQuery().getGroupName()), Job::getGroupName, pageReq.getQuery().getGroupName())
                    .likeRight(StringUtils.hasText(pageReq.getQuery().getJobname()), Job::getName, pageReq.getQuery().getJobname());
        }
        Page<Job> page = jobRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);
        return PageResp.of(page.convert(JobBeanConverter::convert).getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
    }

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

        scheduleJobService.schedule(job);
    }
}
