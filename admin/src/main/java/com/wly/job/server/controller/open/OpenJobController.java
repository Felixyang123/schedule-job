package com.wly.job.server.controller.open;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.server.service.ScheduleJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open/job")
@RequiredArgsConstructor
public class OpenJobController {
    private final ScheduleJobService scheduleJobService;

    /**
     * 注册任务及其实例信息
     * @param jobInfo
     * @return
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody JobInfo jobInfo) {
        scheduleJobService.registerJob(jobInfo);
        return Result.success();
    }

    /**
     * 注册任务实例信息
     * @param instance
     * @return
     */
    @PostMapping("/instance/register")
    public Result<Void> registerInstance(@RequestBody JobInstance instance) {
        scheduleJobService.registerInstance(instance);
        return Result.success();
    }
}
