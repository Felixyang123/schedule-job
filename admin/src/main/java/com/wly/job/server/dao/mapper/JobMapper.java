package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Job;

/**
 * 作业表（job）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；删除走 MyBatis-Plus 逻辑删除配置，生成 deleted=1 的
 * Update 语句而非物理删除。
 */
public interface JobMapper extends BaseMapper<Job> {
}
