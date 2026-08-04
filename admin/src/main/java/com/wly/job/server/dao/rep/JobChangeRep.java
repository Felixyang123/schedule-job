package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.mapper.JobChangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JobChangeRep {

    private final JobChangeMapper mapper;

    public void record(Long jobId, Integer changeType, String operator, String requestId, String jobName) {
        JobChange change = new JobChange();
        change.setJobId(jobId);
        change.setChangeType(changeType);
        change.setOperator(operator);
        change.setRequestId(requestId);
        change.setJobName(jobName);
        change.setCreateTime(new Date());
        mapper.insert(change);
    }

    public List<JobChange> listAfter(long watermark, int limit) {
        return mapper.selectList(Wrappers.<JobChange>lambdaQuery()
                .gt(JobChange::getId, watermark)
                .orderByAsc(JobChange::getId)
                .last("LIMIT " + limit));
    }

    public long maxId() {
        JobChange last = mapper.selectOne(Wrappers.<JobChange>lambdaQuery()
                .select(JobChange::getId)
                .orderByDesc(JobChange::getId)
                .last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    public int deleteUpTo(long watermark) {
        return mapper.delete(Wrappers.<JobChange>lambdaQuery()
                .le(JobChange::getId, watermark));
    }
}
