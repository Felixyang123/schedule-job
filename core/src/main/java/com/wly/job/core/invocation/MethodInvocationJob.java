package com.wly.job.core.invocation;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.core.common.ReflectionParameterConverter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

@Slf4j
public record MethodInvocationJob(Method method, Object target, String jobname,
                                  List<InvocationHook> hooks) implements InnerJob {
    @Override
    public ScheduleJobResponse execute(ScheduleJobRequest request) {
        log.debug("Invoke job: {}", jobname);

        try {
            Type[] parameterTypes = method.getGenericParameterTypes();
            Object result;
            if (parameterTypes.length > 0) {
                // 获取第一个参数
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
        } catch (Exception e) {
            log.error("Schedule method invocation error: ", e);
            return ScheduleJobResponse.builder()
                    .success(false)
                    .error(e.getMessage())
                    .requestId(request.getRequestId())
                    .build();
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
