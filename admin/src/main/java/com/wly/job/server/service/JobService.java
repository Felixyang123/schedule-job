package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.utils.CronUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public record JobService(JobRep jobRep, ScheduleJobService scheduleJobService) {

    public void edit(EditJobReq req) {
        if (StringUtils.hasText(req.getCron())) {
            CronUtils.checkCronExpression(req.getCron());
        }
        jobRep.updateById(JobBeanConverter.convert(req));
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
