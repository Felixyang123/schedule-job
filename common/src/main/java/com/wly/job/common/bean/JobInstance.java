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

    private String discoveryName;

    private String host;

    private Integer port;

    private Integer status;

    private Date expireTime;

    public String getInstanceKey() {
        return this.discoveryName + ":" + this.host + ":" + this.port;
    }
}
