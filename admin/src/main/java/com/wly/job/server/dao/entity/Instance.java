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
 * 执行器实例实体，对应 {@code instance} 表。
 *
 * <p>记录执行器（Worker）的物理节点信息：IP、Netty 监听端口、在线状态与心跳活性。
 * 实例通过开放接口周期性上报心跳（HTTP /open/job/instance/register），主节点据此维护
 * {@code expireTime} 并判定 {@code ONLINE / OFFLINE}；调度派发时从注册中心发现可用实例做负载均衡。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "instance", autoResultMap = true)
public class Instance {
    /** 下线 */
    public static final Integer OFFLINE = 0;
    /** 在线 */
    public static final Integer ONLINE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实例名（通常为执行器节点标识） */
    private String name;

    /** 实例 IP 地址 */
    private String host;

    /** 实例 Netty 监听端口 */
    private Integer port;

    /**
     * 凭证身份（ADR-0006）：应用身份（Worker 配置 application-name）。
     * 由心跳请求体上报，服务端按鉴权结果写入，供派发签名与 activate 就绪校验。
     */
    private String applicationName;

    /** 凭证身份环境（Worker 从 activeProfiles 提取） */
    private String env;

    /** 该实例当前所用凭证版本（服务端按鉴权结果写入，不信任 Worker 自报） */
    private Integer credentialVersion;

    /**
     * 状态
     * 0: 下线 1: 在线
     */
    private Integer status;

    /** 心跳到期时间，超过后视为实例失活（由注册中心清扫或重新上报刷新） */
    private Date expireTime;

    private Date createTime;

    private Date updateTime;

    private String creator;

    private String updater;

    /**
     * 初始化新建实例的默认字段：置为在线并初始化时间戳。
     * 执行器首次注册入库前调用。
     *
     * @return 当前实例对象（支持链式调用）
     */
    public Instance init() {
        this.createTime = new Date();
        this.updateTime = this.createTime;
        this.status = ONLINE;
        return this;
    }
}
