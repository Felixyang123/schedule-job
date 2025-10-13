package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.mapper.ScheduleRecMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRecRep extends ServiceImpl<ScheduleRecMapper, ScheduleRec> {
}
