package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.mapper.GroupMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRep extends ServiceImpl<GroupMapper, JobGroup> {
}
