package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** Local Admin account persisted by job, independent from the SSO server. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "admin_user", autoResultMap = true)
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String accountId;

    private String username;

    private String passwordHash;

    private Boolean enabled;

    private Boolean mustChangePassword;

    private Date passwordChangedAt;

    private Date createTime;

    private Date updateTime;
}
