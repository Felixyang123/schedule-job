package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.mapper.GroupMapper;
import org.springframework.stereotype.Repository;

/**
 * 作业分组仓储层（Repository），继承 MyBatis-Plus {@link ServiceImpl} 提供 job_group 表的通用 CRUD。
 */
@Repository
public class GroupRep extends ServiceImpl<GroupMapper, JobGroup> {
}
