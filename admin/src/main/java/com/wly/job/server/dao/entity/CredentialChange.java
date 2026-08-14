package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 凭证变更源记录实体，对应 {@code credential_change} 表（ADR-0006）。
 *
 * <p>凭证写路径（prepare / activate / cancel / revoke）与凭证写入同事务地追加一条记录，
 * <b>仅作缓存失效信号</b>——不携带 token_hash / salt 等密码材料（Spec 决策 #27）。
 * 消费者是<b>每个 Admin 节点</b>（/open/** 校验在所有节点执行，不做 leader 门控）；
 * 记录清理（按 create_time 保留 1h）为纯 housekeeping，应当门控。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "credential_change")
public class CredentialChange {

    /** 变更类型：prepare */
    public static final int CHANGE_PREPARE = 1;

    /** 变更类型：activate */
    public static final int CHANGE_ACTIVATE = 2;

    /** 变更类型：cancel */
    public static final int CHANGE_CANCEL = 3;

    /** 变更类型：revoke */
    public static final int CHANGE_REVOKE = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用身份 */
    private String applicationName;

    /** 环境 */
    private String env;

    /** 变更类型（1 prepare / 2 activate / 3 cancel / 4 revoke） */
    private Integer changeType;

    /** 操作人 */
    private String operator;

    /** 变更时间 */
    private Date createTime;
}
