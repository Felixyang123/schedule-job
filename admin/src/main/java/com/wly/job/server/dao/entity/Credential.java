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
 * 凭证身份实体，对应 {@code credential} 表（ADR-0006）。
 *
 * <p>以「应用身份 + 环境」为维度（唯一键 {@code uk_app_env}）发放凭证：
 * {@code active_version} / {@code pending_version} 为指向
 * {@link CredentialVersion} 的两版状态指针，指针即业务状态，NULL 表示无该状态版本。
 * 本表只保存明文凭证的不可逆摘要（见 CredentialVersion），不落明文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "credential")
public class Credential {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用身份（Worker 配置 schedule-job.application-name） */
    private String applicationName;

    /** 环境（Worker 从 activeProfiles 提取） */
    private String env;

    /** 当前生效版本号（指针，NULL 表示无 active 版本） */
    private Integer activeVersion;

    /** 待激活版本号（轮换 prepare 后建立，NULL 表示无 pending 版本） */
    private Integer pendingVersion;

    private Date createTime;

    private String creator;

    private Date updateTime;

    private String updater;
}
