package com.wly.job.samples.sample.job;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import com.wly.job.starter.annotation.ScheduleJob;
import org.springframework.stereotype.Component;

@Component
public class DemoJob {

    @ScheduleJob(name = "DemoJob", cron = "0/5 * * * * ?", description = "Demo Job", type = JobTypeEnum.GENERAL, strategy = ScheduleStrategyEnum.ROUND_ROBIN)
    public String execute() {
        System.out.println("Demo Job execute...");

        return "Demo Job execute...";
    }
}
