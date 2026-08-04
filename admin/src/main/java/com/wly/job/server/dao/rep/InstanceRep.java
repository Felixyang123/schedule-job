package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.mapper.InstanceMapper;
import org.springframework.stereotype.Repository;

/**
 * 执行器实例仓储层（Repository），继承 MyBatis-Plus {@link ServiceImpl} 提供 instance 表的通用 CRUD。
 *
 * <p>与 {@code LocalCacheJobInstanceStorage} 共同承载执行器实例元数据：本地缓存支撑调度发现，
 * 本仓储负责实例表的持久化维护（如在线状态与心跳到期时间）。
 */
@Repository
public class InstanceRep extends ServiceImpl<InstanceMapper, Instance> {
}
