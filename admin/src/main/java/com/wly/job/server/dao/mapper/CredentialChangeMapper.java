package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.CredentialChange;

/**
 * 凭证变更源表（credential_change）MyBatis-Plus Mapper。
 *
 * <p>仅作缓存失效信号（ADR-0006），消费者为每个 Admin 节点（不做 leader 门控）；
 * 本阶段无管理接口写入口，表结构先建好供下轮使用。
 */
public interface CredentialChangeMapper extends BaseMapper<CredentialChange> {
}
