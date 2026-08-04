package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.JobGroup;

/**
 * 作业分组表（job_group）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；当前分组表仅承载分组名与描述，不参与调度决策。
 */
public interface GroupMapper extends BaseMapper<JobGroup> {

}
