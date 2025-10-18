package com.wly.job.common.bean;

import lombok.Data;

@Data
public class PageReq<T> {
    private Integer pageNum;
    private Integer pageSize;

    private T query;
}
