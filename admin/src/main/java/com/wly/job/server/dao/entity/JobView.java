package com.wly.job.server.dao.entity;

public record JobView(Long id, String name, String cron, String executeParam,
                      Integer strategy, Integer type) {

    public static JobView of(Job job) {
        return new JobView(job.getId(), job.getName(), job.getCron(),
                job.getExecuteParam(), job.getStrategy(), job.getType());
    }

    public Job toJob() {
        return Job.builder()
                .id(id)
                .name(name)
                .cron(cron)
                .executeParam(executeParam)
                .strategy(strategy)
                .type(type)
                .build();
    }
}
