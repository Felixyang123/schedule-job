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

/**
 * 调度记录查询服务（record 形式，无状态只读服务）。
 *
 * <p>负责调度记录（schedule_rec 表）的分页查询，支持按作业 ID、请求 ID 精确过滤以及按作业名
 * 反查。作业名过滤时先查 job 表得到 jobId 集合，无匹配直接返回空页，避免退化为全表扫描。
 */
@Service
public record ScheduleRecService(ScheduleRecRep recRep, JobRep jobRep) {

    /**
     * 分页查询调度记录（按 ID 倒序）。
     *
     * <p>按作业名查询时，先由 {@code job} 表反查 jobId 列表；若结果为空则直接返回空页。
     * 返回结果中统一回填作业名：按作业名过滤时直接使用查询条件，否则批量查 job 表组装映射。
     *
     * @param pageReq 分页请求与查询条件
     * @return 调度记录分页结果（含作业名）
     */
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

    /**
     * 查询调度记录详情（管控后台"详情"入口，按 requestId 精确匹配）。
     *
     * @param requestId 调度链路追踪请求 ID
     * @return 调度记录视图对象；不存在时返回 null（由调用方包装为"调度记录不存在"）
     */
    public ScheduleRecResp detail(String requestId) {
        ScheduleRec rec = recRep.getOne(Wrappers.<ScheduleRec>lambdaQuery().eq(ScheduleRec::getRequestId, requestId));
        return rec == null ? null : JobBeanConverter.convert(rec);
    }
}
