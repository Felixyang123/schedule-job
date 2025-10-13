package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobInfo {

    private JobInstance instance;

    private String jobname;

    private String description;

    private String cron;

    private String executeParam;

    private Integer strategy;

    private Integer type;

    private String group;
}
