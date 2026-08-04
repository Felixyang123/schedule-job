package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.mapper.JobChangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 作业变更源（Change Feed）仓储层（Repository）。
 *
 * <p>封装 {@code job_change} 表的写入与消费。写路径埋点（record）与 Job 行写入同事务执行
 * （失败重试 REQUEUE 为独立插入）；主节点通过 {@link #listAfter} 按 id 水印增量消费、
 * {@link #maxId} 同步水印、{@link #deleteUpTo} 清理已消费记录。
 */
@Repository
@RequiredArgsConstructor
public class JobChangeRep {

    private final JobChangeMapper mapper;

    /**
     * 追加一条变更记录。调用方必须保证与 Job 行写入处于同一事务（REQUEUE 除外）。
     *
     * @param jobId      发生变更的作业 ID
     * @param changeType 变更类型（见 {@link com.wly.job.server.enumeration.JobChangeTypeEnum}）
     * @param operator   操作人（系统写路径传 "system"）
     * @param requestId  关联的调度请求 ID，可为空
     * @param jobName    作业名快照，便于排查
     */
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

    /**
     * 按 id 水印增量拉取变更记录（主节点对账消费）。
     *
     * @param watermark 上次已消费的最大记录 ID（不含）
     * @param limit     单批拉取条数（调度侧固定 LIMIT 500）
     * @return 一批按 id 升序的变更记录
     */
    public List<JobChange> listAfter(long watermark, int limit) {
        return mapper.selectList(Wrappers.<JobChange>lambdaQuery()
                .gt(JobChange::getId, watermark)
                .orderByAsc(JobChange::getId)
                .last("LIMIT " + limit));
    }

    /** @return 当前最大记录 ID（水印同步 / 接管主节点时初始化用，无记录返回 0） */
    public long maxId() {
        JobChange last = mapper.selectOne(Wrappers.<JobChange>lambdaQuery()
                .select(JobChange::getId)
                .orderByDesc(JobChange::getId)
                .last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    /**
     * 删除已消费到指定水印的变更记录（周期性清理，防止表无限膨胀）。
     *
     * @param watermark 删除 id 小于等于该值的记录
     * @return 删除条数
     */
    public int deleteUpTo(long watermark) {
        return mapper.delete(Wrappers.<JobChange>lambdaQuery()
                .le(JobChange::getId, watermark));
    }
}
