package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.mapper.JobMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JobRep extends ServiceImpl<JobMapper, Job> {

    public List<Job> batchQueryJobsByCursor(long cursor, int limit) {
        return list(Wrappers.<Job>lambdaQuery().eq(Job::getStatus, Job.ENABLE).gt(Job::getId, cursor).last("LIMIT " + limit));
    }

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
