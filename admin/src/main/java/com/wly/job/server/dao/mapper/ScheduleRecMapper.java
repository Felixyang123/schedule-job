package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.ScheduleRec;

/**
 * 调度执行记录表（schedule_rec）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；调度记录的写入通过 ScheduleRecQueue 异步攒批
 * saveBatch 落库，保障不阻塞主调度循环。
 */
public interface ScheduleRecMapper extends BaseMapper<ScheduleRec> {
}
