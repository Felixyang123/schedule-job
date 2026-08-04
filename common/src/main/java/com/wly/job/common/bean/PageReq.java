package com.wly.job.common.bean;

import lombok.Data;

/**
 * 分页查询请求 DTO：携带页码、每页大小与可选的查询条件对象 {@code query}，
 * 供 Admin 管控后台作业/分组/调度记录列表查询使用。
 */
@Data
public class PageReq<T> {
    private Integer pageNum;
    private Integer pageSize;

    private T query;
}
