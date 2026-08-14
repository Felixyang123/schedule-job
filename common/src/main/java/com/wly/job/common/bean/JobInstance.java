package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 执行器实例（Worker Instance）DTO：描述一个物理执行节点。
 * <p>
 * 携带执行器的发现键（discoveryKey，分组模式取应用名、否则取作业名）、宿主 IP 与 Netty 监听端口，
 * 通过 HTTP 接口 {@code /open/job/instance/register} 向 Admin 注册并周期性续约心跳；
 * {@code expireTime} 为心跳租约到期时间，超期后 Admin 侧视为失活。
 *
 * <p>凭证身份（ADR-0006）：{@code applicationName} / {@code env} 为 Worker 侧身份，
 * 心跳注册时随请求体上报（与 {@code X-Job-Group}/{@code X-Job-Env} Header 一致，由 Controller 二次校验）；
 * Admin 持久化到 {@code instance} 表，供派发时按身份取凭证签名与 {@code activate} 就绪校验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobInstance {

    private String discoveryKey;

    /** 应用身份（Worker 配置 schedule-job.application-name，凭证身份维度之一） */
    private String applicationName;

    /** 环境（Worker 从 activeProfiles 提取，凭证身份维度之一） */
    private String env;

    /** 该实例当前所用凭证版本（Admin 按鉴权结果写入，不信任 Worker 自报） */
    private Integer credentialVersion;

    private String host;

    private Integer port;

    private Integer status;

    private Date expireTime;

    public String getInstanceKey() {
        return this.discoveryKey + ":" + this.host + ":" + this.port;
    }

    public boolean isExpired() {
        return this.expireTime == null || new Date().after(this.expireTime);
    }
}
