package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.mapper.InstanceMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InstanceRep extends ServiceImpl<InstanceMapper, Instance> {
}
