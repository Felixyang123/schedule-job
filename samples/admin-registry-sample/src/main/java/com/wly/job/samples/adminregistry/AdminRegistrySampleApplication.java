package com.wly.job.samples.adminregistry;

import com.wly.job.starter.annotation.EnableScheduleJob;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduleJob
public class AdminRegistrySampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminRegistrySampleApplication.class, args);
    }
}
