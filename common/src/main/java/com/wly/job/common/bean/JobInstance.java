package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

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
        return new Date().after(this.expireTime);
    }
}
