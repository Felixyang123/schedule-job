package com.wly.job.samples.adminregistry.job;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import com.wly.job.starter.annotation.ScheduleJob;
import org.springframework.stereotype.Component;

@Component
public class DemoJob {

    @ScheduleJob(name = "AdminRegistryDemoJob", cron = "0/5 * * * * ?", description = "Demo Job", type = JobTypeEnum.GENERAL, strategy = ScheduleStrategyEnum.ROUND_ROBIN)
    public String execute() {
        System.out.println("Demo Job execute...");

        return "Demo Job execute...";
    }

    @ScheduleJob(name = "AdminRegistryDemoJob2", cron = "0/10 * * * * ?", description = "Demo Job2", type = JobTypeEnum.GENERAL, strategy = ScheduleStrategyEnum.ROUND_ROBIN)
    public String execute2() {
        System.out.println("Demo Job2 execute...");

        return "Demo Job2 execute...";
    }

    @ScheduleJob(name = "AdminRegistryDemoJob3", cron = "0/8 * * * * ?", description = "Demo Job3", type = JobTypeEnum.GENERAL, strategy = ScheduleStrategyEnum.ROUND_ROBIN)
    public String execute3() {
        System.out.println("Demo Job3 execute...");

        return "Demo Job3 execute...";
    }

    @ScheduleJob(name = "AdminRegistryDemoJob4", cron = "0/1 * * * * ?", description = "Demo Job4", type = JobTypeEnum.GENERAL, strategy = ScheduleStrategyEnum.ROUND_ROBIN)
    public String execute4() {
        System.out.println("Demo Job4 execute...");

        return "Demo Job4 execute...";
    }
}
