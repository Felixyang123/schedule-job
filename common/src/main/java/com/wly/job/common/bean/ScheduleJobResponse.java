package com.wly.job.common.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleJobResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = -251810562076485580L;

    private String requestId;

    private Object result;

    private String error;

    private boolean success;
}