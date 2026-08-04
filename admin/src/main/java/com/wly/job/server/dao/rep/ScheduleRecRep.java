package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.mapper.ScheduleRecMapper;
import org.springframework.stereotype.Repository;

/**
 * 调度记录仓储层（Repository），继承 MyBatis-Plus {@link ServiceImpl} 提供 schedule_rec 表的通用 CRUD。
 *
 * <p>调用方包括：调度记录分页查询（ScheduleRecService）与调度记录的异步攒批落库（ScheduleRecQueue）。
 */
@Repository
public class ScheduleRecRep extends ServiceImpl<ScheduleRecMapper, ScheduleRec> {
}
