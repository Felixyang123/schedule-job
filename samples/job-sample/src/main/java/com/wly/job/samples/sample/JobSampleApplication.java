package com.wly.job.samples.sample;

import com.wly.job.starter.annotation.EnableScheduleJob;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduleJob
public class JobSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobSampleApplication.class, args);
    }
}
