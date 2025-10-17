package com.wly.job.server.stroage;

import lombok.Data;

import java.util.List;

@Data
public class RefreshContext<T> {

    /**
     * 查询游标
     */
    private long cursor;

    private List<T> newDataCollection;
}
