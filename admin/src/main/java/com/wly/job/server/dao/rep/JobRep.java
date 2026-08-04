package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.mapper.JobMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 作业仓储层（Repository）。
 *
 * <p>封装 {@code job} 表的批量查询，供调度对账（Reconcile）与投影装载使用：
 * 仅查询运行中（status=1）作业，游标按主键 ID 增量拉取，避免一次性加载全表。
 */
@Repository
public class JobRep extends ServiceImpl<JobMapper, Job> {

    /**
     * 按主键游标增量查询运行中作业（全量兜底对账使用）。
     *
     * @param cursor 上次拉取的最大作业 ID（不含）
     * @param limit  单批拉取条数
     * @return 一批运行中且 ID 大于游标的作业
     */
    public List<Job> batchQueryJobsByCursor(long cursor, int limit) {
        return list(Wrappers.<Job>lambdaQuery().eq(Job::getStatus, Job.ENABLE).gt(Job::getId, cursor).last("LIMIT " + limit));
    }

    /**
     * 按主键游标增量查询运行中、未完成作业的轻量投影。
     *
     * <p>投影全量兜底对账使用：仅装载调度决策所需字段（见 {@link JobView}）并过滤单次任务已完成
     * （finished=0）的作业，控制内存占用。
     *
     * @param cursor 上次拉取的最大作业 ID（不含）
     * @param limit  单批拉取条数
     * @return 一批可入队的作业投影
     */
    public List<JobView> batchQueryJobViewsByCursor(long cursor, int limit) {
        return list(Wrappers.<Job>lambdaQuery()
                .select(Job::getId, Job::getName, Job::getCron, Job::getExecuteParam, Job::getStrategy, Job::getType)
                .eq(Job::getStatus, Job.ENABLE)
                .eq(Job::getFinished, 0)
                .gt(Job::getId, cursor)
                .last("LIMIT " + limit))
                .stream()
                .map(JobView::of)
                .toList();
    }
}
