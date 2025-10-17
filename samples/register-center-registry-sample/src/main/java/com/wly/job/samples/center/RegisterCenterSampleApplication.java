package com.wly.job.samples.center;

import com.wly.config.core.annotation.EnableRegistry;
import com.wly.job.starter.annotation.EnableScheduleJob;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduleJob
@EnableRegistry
public class RegisterCenterSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegisterCenterSampleApplication.class, args);
    }
}
