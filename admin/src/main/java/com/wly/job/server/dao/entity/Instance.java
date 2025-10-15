package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "instance", autoResultMap = true)
public class Instance {
    public static final Integer OFFLINE = 0;
    public static final Integer ONLINE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String host;

    private Integer port;

    /**
     * 状态
     * 0: 下线 1: 在线
     */
    private Integer status;

    private Date expireTime;

    private Date createTime;

    private Date updateTime;

    private String creator;

    private String updater;

    public Instance init() {
        this.createTime = new Date();
        this.updateTime = this.createTime;
        this.status = ONLINE;
        return this;
    }
}
