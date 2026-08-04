package com.wly.job.server.exception;

import com.wly.job.common.bean.Result;
import com.wly.job.common.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（RestControllerAdvice）。
 *
 * <p>统一拦截 Controller 层异常并转换为标准 {@link Result} 响应：
 * <ul>
 *   <li>{@link ScheduleException}（业务异常基类）：携带业务错误码返回；</li>
 *   <li>{@link RuntimeException}：记录错误日志后返回失败结果；</li>
 *   <li>{@link Exception}：兜底处理所有未捕获异常。</li>
 * </ul>
 * <p>注意：调度派发链路的非致命异常不会流经此处，而是由调度回调记录到调度记录（ScheduleRec）中。
 */
@RestControllerAdvice
@Slf4j
public class DefineExceptionHandler {

    /** 兜底异常处理（未命中更具体 handler 的所有异常） */
    @ExceptionHandler(value = Exception.class)
    public Result<Void> handle(Exception e) {
        log.error("handle exception: ", e);
        return Result.fail(e.getMessage());
    }

    /** 运行时异常处理 */
    @ExceptionHandler(value = RuntimeException.class)
    public Result<Void> handle(RuntimeException e) {
        log.error("handle runtime exception: ", e);
        return Result.fail(e.getMessage());
    }

    /** 业务异常处理（携带业务错误码返回，供前端识别） */
    @ExceptionHandler(value = ScheduleException.class)
    public Result<Void> handle(ScheduleException e) {
        log.error("handle sso biz exception: ", e);
        return Result.fail(e.getErrorCode(), e.getMessage());
    }
}
