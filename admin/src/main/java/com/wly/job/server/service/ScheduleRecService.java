package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.pojo.req.QueryScheduleRecReq;
import com.wly.job.server.pojo.resp.ScheduleRecResp;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public record ScheduleRecService(ScheduleRecRep recRep, JobRep jobRep) {

    public PageResp<ScheduleRecResp> page(PageReq<QueryScheduleRecReq> pageReq) {
        QueryScheduleRecReq query = pageReq.getQuery();
        LambdaQueryWrapper<ScheduleRec> wrapper = Wrappers.<ScheduleRec>lambdaQuery().orderByDesc(ScheduleRec::getId);
        if (query != null) {
            wrapper.eq(query.getJobId() != null, ScheduleRec::getJobId, query.getJobId())
                    .eq(StringUtils.hasText(query.getRequestId()), ScheduleRec::getRequestId, query.getRequestId());

            if (StringUtils.hasText(query.getJobname())) {
                List<Job> jobs = jobRep.list(Wrappers.<Job>lambdaQuery().eq(Job::getName, query.getJobname()));
                List<Long> jobIds = jobs.stream().map(Job::getId).toList();
                wrapper.in(!jobIds.isEmpty(), ScheduleRec::getJobId, jobIds);
            }
        }

        Page<ScheduleRec> page = recRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);

        if (CollectionUtils.isEmpty(page.getRecords())) {
            return PageResp.of(new ArrayList<>(), page.getTotal(), page.getSize(), page.getCurrent());
        }

        if (query != null && StringUtils.hasText(query.getJobname())) {
            List<ScheduleRecResp> records = page.convert(rec -> {
                ScheduleRecResp resp = JobBeanConverter.convert(rec);
                resp.setJobName(query.getJobname());
                return resp;
            }).getRecords();

            return PageResp.of(records, page.getTotal(), page.getSize(), page.getCurrent());
        }

        List<Long> jobIds = page.getRecords().stream().map(ScheduleRec::getJobId).toList();
        Map<Long, String> jobIdNameMap = jobRep.listByIds(jobIds).stream().collect(Collectors.toMap(Job::getId, Job::getName));
        List<ScheduleRecResp> records = page.convert(rec -> {
            ScheduleRecResp scheduleRecResp = JobBeanConverter.convert(rec);
            scheduleRecResp.setJobName(jobIdNameMap.get(rec.getJobId()));
            return scheduleRecResp;
        }).getRecords();
        return PageResp.of(records, page.getTotal(), page.getSize(), page.getCurrent());
    }
}
