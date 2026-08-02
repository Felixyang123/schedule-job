package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * 任务管理
 */
@RestController
@RequestMapping("/admin/job")
@RequiredArgsConstructor
public class JobController {
    private final JobService jobService;

    /**
     * 分页查询任务列表
     *
     * @param pageReq
     * @return
     */
    @PostMapping("/page")
    public Result<PageResp<JobResp>> page(@RequestBody PageReq<QueryJobReq> pageReq) {
        return Result.success(jobService.page(pageReq));
    }

    /**
     * 任务详情
     *
     * @param id
     * @return
     */
    @GetMapping("/detail")
    public Result<JobResp> detail(@RequestParam("id") Long id) {
        Job job = jobService.jobRep().getById(id);
        if (job == null) {
            return Result.fail("任务不存在");
        }
        return Result.success(JobBeanConverter.convert(job));
    }

    /**
     * 编辑任务
     *
     * @param req
     * @return
     */
    @PostMapping("/edit")
    public Result<Void> edit(@RequestBody EditJobReq req) {
        jobService.edit(req);
        return Result.success();
    }

    /**
     * 切换任务状态
     *
     * @param id
     * @return
     */
    @PostMapping("/switch")
    public Result<Void> switchStatus(@RequestParam("id") Long id) {
        Job job = jobService.jobRep().getById(id);

        if (Objects.isNull(job)) {
            return Result.fail("任务不存在");
        }

        job.setStatus(Objects.equals(job.getStatus(), Job.ENABLE) ? Job.UNABLE : Job.ENABLE);
        jobService.jobRep().updateById(job);
        return Result.success();
    }

    /**
     * 执行任务
     *
     * @param req
     * @return
     */
    @PostMapping("/exec")
    public Result<Void> exec(@RequestBody ExecJobReq req) {
        jobService.exec(req);
        return Result.success();
    }
}
