package com.wly.job.server.pojo.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupResp {

    private Long id;

    private String name;

    private String description;

    private Date createTime;

    private String creator;
}
