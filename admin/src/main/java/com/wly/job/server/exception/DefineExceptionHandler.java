package com.wly.job.server.exception;

import com.wly.job.common.bean.Result;
import com.wly.job.common.exception.ScheduleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class DefineExceptionHandler {

    @ExceptionHandler(value = Exception.class)
    public Result<Void> handle(Exception e) {
        log.error("handle exception: ", e);
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(value = RuntimeException.class)
    public Result<Void> handle(RuntimeException e) {
        log.error("handle runtime exception: ", e);
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(value = ScheduleException.class)
    public Result<Void> handle(ScheduleException e) {
        log.error("handle sso biz exception: ", e);
        return Result.fail(e.getErrorCode(), e.getMessage());
    }
}
