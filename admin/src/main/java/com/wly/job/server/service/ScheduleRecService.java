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
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public record ScheduleRecService(ScheduleRecRep recRep, JobRep jobRep) {

    public PageResp<ScheduleRecResp> page(PageReq<QueryScheduleRecReq> pageReq) {
        QueryScheduleRecReq query = pageReq.getQuery();
        LambdaQueryWrapper<ScheduleRec> wrapper = Wrappers.<ScheduleRec>lambdaQuery().orderByDesc(ScheduleRec::getId);
        List<Long> jobIds = null;
        if (query != null) {
            wrapper.eq(query.getJobId() != null, ScheduleRec::getJobId, query.getJobId())
                    .eq(StringUtils.hasText(query.getRequestId()), ScheduleRec::getRequestId, query.getRequestId());

            if (StringUtils.hasText(query.getJobname())) {
                jobIds = jobRep.list(Wrappers.<Job>lambdaQuery().eq(Job::getName, query.getJobname()))
                        .stream().map(Job::getId).toList();
                if (jobIds.isEmpty()) {
                    // 按作业名过滤无匹配时直接返回空页，避免退化为全表查询
                    return PageResp.of(List.of(), 0, pageReq.getPageSize(), pageReq.getPageNum());
                }
                wrapper.in(ScheduleRec::getJobId, jobIds);
            }
        }

        Page<ScheduleRec> page = recRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);

        Map<Long, String> jobIdNameMap = jobIds == null
                ? jobRep.listByIds(page.getRecords().stream().map(ScheduleRec::getJobId).toList())
                        .stream().collect(Collectors.toMap(Job::getId, Job::getName))
                : jobIds.stream().collect(Collectors.toMap(id -> id, id -> query.getJobname()));

        List<ScheduleRecResp> records = page.convert(rec -> {
            ScheduleRecResp resp = JobBeanConverter.convert(rec);
            resp.setJobName(jobIdNameMap.get(rec.getJobId()));
            return resp;
        }).getRecords();
        return PageResp.of(records, page.getTotal(), page.getSize(), page.getCurrent());
    }
}
