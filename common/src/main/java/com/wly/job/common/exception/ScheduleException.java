package com.wly.job.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScheduleException extends RuntimeException {
    private static final String DEFAULT_ERROR_CODE = "1000";
    @Serial
    private static final long serialVersionUID = -1740862570245714519L;

    private String errorCode;

    private String errorMsg;

    public ScheduleException(String errorCode, String errorMsg) {
        super(errorMsg);
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }

    public ScheduleException(String errorMsg) {
        super(errorMsg);
        this.errorCode = DEFAULT_ERROR_CODE;
        this.errorMsg = errorMsg;
    }

    public ScheduleException(String errorMsg, Throwable cause) {
        super(errorMsg, cause);
        this.errorMsg = errorMsg;
    }
}
