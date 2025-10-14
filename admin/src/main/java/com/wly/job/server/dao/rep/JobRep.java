package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.mapper.JobMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JobRep extends ServiceImpl<JobMapper, Job> {

    public List<Job> batchQueryJobsByCursor(long cursor, int limit) {
        return list(Wrappers.<Job>lambdaQuery().eq(Job::getStatus, Job.ENABLE).gt(Job::getId, cursor).last("LIMIT " + limit));
    }
}
