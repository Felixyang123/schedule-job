package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页查询结果 DTO：返回当前页记录列表 {@code records} 与总数 {@code total}，
 * 与 {@link PageReq} 对应，供 Admin 管控后台列表接口使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResp<T> {
    private List<T> records;
    private long total;
    private long size;
    private long current;

    public static <T> PageResp<T> of(List<T> records, long total, long size, long current) {
        return PageResp.<T>builder()
                .records(records)
                .total(total)
                .size(size)
                .current(current)
                .build();
    }
}
