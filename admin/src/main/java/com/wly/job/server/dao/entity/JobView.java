package com.wly.job.server.dao.entity;

/**
 * 作业轻量投影（record，不可变）。
 *
 * <p>调度队列与 {@code queuedJobs} 仅持有此投影而非完整 {@link Job} 实体，避免在十万级任务量下
 * 将整行元数据加载进内存（ADR-0005）。投影仅包含调度决策所需字段：
 * id / name / cron / executeParam / strategy / type，不含状态、时间戳等管理字段。
 */
public record JobView(Long id, String name, String cron, String executeParam,
                      Integer strategy, Integer type) {

    /** 从完整作业实体提取调度所需字段，构造投影 */
    public static JobView of(Job job) {
        return new JobView(job.getId(), job.getName(), job.getCron(),
                job.getExecuteParam(), job.getStrategy(), job.getType());
    }

    /** 反向还原为作业实体（仅填充投影字段，用于投递到执行器的载荷组装） */
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
