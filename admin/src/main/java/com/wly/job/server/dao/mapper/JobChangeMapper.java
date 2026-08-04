package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.JobChange;

/**
 * 作业变更源表（job_change）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；变更记录的写入须与 Job 行写入同事务（REQUEUE 独立插入），
 * 主节点按 id 水印增量消费。
 */
public interface JobChangeMapper extends BaseMapper<JobChange> {
}
