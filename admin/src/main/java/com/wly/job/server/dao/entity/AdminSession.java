package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** Persistent session material used by the SSO Core LoginStorage adapter. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "admin_session", autoResultMap = true)
public class AdminSession {

    @TableId(type = IdType.INPUT)
    private String accountId;

    private String accountName;

    private String signature;

    private Date expireTime;

    private String rolesJson;

    private String permissionsJson;

    private Date createTime;

    private Date updateTime;
}
