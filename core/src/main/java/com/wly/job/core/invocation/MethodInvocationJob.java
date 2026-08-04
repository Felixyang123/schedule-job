package com.wly.job.core.invocation;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.bean.ExecuteJobContext;
import com.wly.job.core.common.ReflectionParameterConverter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 基于反射的方法调用任务：将标注了 {@code @ScheduleJob} 的方法包装为 {@link InnerJob}。
 * <p>
 * 执行时通过 {@link ReflectionParameterConverter} 将调度请求中的 executeParam 字符串
 * 转换为方法参数（方法必须声明 0 或 1 个参数），调用前后依次触发 {@link InvocationHook}
 * 钩子（如链路追踪、性能监控）。返回值若本身就是 {@link ScheduleJobResponse} 则原样返回
 * 并回填 requestId，否则包装为 success 响应；异常统一捕获为失败响应，不外泄到主线程。
 * <p>
 * 线程模型：record 实例不可变，可在多线程（业务线程池）下并发执行；调用期间通过
 * {@link com.wly.job.core.bean.ExecuteJobContext} 暴露当前请求上下文，finally 中清理。
 */
@Slf4j
public record MethodInvocationJob(Method method, Object target, String jobname,
                                  List<InvocationHook> hooks) implements InnerJob {
    @Override
    public ScheduleJobResponse execute(ScheduleJobRequest request) {
        log.debug("Invoke job: {}", jobname);

        try {
            ExecuteJobContext.setRequest(request);
            Type[] parameterTypes = method.getGenericParameterTypes();
            Object result;
            if (parameterTypes.length > 1) {
                throw new ScheduleException("Schedule job method must declare 0 or 1 parameter, method: " + method.getName());
            } else if (parameterTypes.length == 1) {
                Object param = ReflectionParameterConverter.convertStringToType(request.getExecuteParam(), parameterTypes[0]);
                before(param);
                result = method.invoke(target, param);
            } else {
                before();
                result = method.invoke(target);
            }
            after(result);

            Class<?> returnType = method.getReturnType();
            if (result != null && ScheduleJobResponse.class.isAssignableFrom(returnType)) {
                ScheduleJobResponse response = (ScheduleJobResponse) result;
                response.setRequestId(request.getRequestId());
                return response;
            }

            return ScheduleJobResponse.builder()
                    .success(true)
                    .result(result)
                    .requestId(request.getRequestId())
                    .build();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("Schedule method invocation error: ", cause);
            return ScheduleJobResponse.builder()
                    .success(false)
                    .error(cause.getMessage())
                    .requestId(request.getRequestId())
                    .build();
        } catch (Exception e) {
            log.error("Schedule method invocation error: ", e);
            return ScheduleJobResponse.builder()
                    .success(false)
                    .error(e.getMessage())
                    .requestId(request.getRequestId())
                    .build();
        } finally {
            ExecuteJobContext.clear();
        }
    }

    @Override
    public String jobname() {
        return jobname;
    }

    private void before(Object... args) {
        for (InvocationHook hook : hooks) {
            hook.beforeInvoke(target, method, args);
        }
    }

    private void after(Object... results) {
        for (InvocationHook hook : hooks) {
            hook.afterInvoke(target, method, results);
        }
    }
}
