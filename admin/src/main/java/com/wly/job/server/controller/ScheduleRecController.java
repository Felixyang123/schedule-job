package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.pojo.req.QueryScheduleRecReq;
import com.wly.job.server.pojo.resp.ScheduleRecResp;
import com.wly.job.server.service.ScheduleRecService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 调度记录管理
 */
@RestController
@RequestMapping("/admin/schedule/rec")
@RequiredArgsConstructor
public class ScheduleRecController {
    private final ScheduleRecService recService;

    /**
     * 分页查询调度记录列表
     *
     * @param pageReq
     * @return
     */
    @PostMapping("/page")
    public Result<PageResp<ScheduleRecResp>> page(@RequestBody PageReq<QueryScheduleRecReq> pageReq) {
        return Result.success(recService.page(pageReq));
    }

    /**
     * 查询调度记录详情
     *
     * @param requestId
     * @return
     */
    @GetMapping("/detail")
    public Result<ScheduleRecResp> detail(@RequestParam("requestId") String requestId) {
        ScheduleRecResp rec = recService.detail(requestId);
        if (rec == null) {
            return Result.fail("调度记录不存在");
        }
        return Result.success(rec);
    }

}
