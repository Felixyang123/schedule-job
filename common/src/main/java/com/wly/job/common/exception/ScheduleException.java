package com.wly.job.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 业务异常基类：框架内所有自定义业务异常必须继承本类（继承自 RuntimeException）。
 * <p>
 * 携带统一错误码 {@code errorCode}（默认 "1000"）与错误消息 {@code errorMsg}，
 * 跨模块（Admin/Worker）抛递时保持语义一致，便于上层统一捕获与记录。
 */
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
