package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 执行器实例（Worker Instance）DTO：描述一个物理执行节点。
 * <p>
 * 携带执行器的发现键（discoveryKey，分组模式取任务组名、否则取作业名）、宿主 IP 与 Netty 监听端口，
 * 通过 HTTP 接口 {@code /open/job/instance/register} 向 Admin 注册并周期性续约心跳；
 * {@code expireTime} 为心跳租约到期时间，超期后 Admin 侧视为失活。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobInstance {

    private String discoveryKey;

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
