package com.wly.job.core.invocation;

import java.lang.reflect.Method;

/**
 * 调用钩子接口：在执行器反射执行作业方法的前后进行拦截处理。
 * <p>
 * 实现类通过 {@link com.wly.job.core.ScheduleJobCoreFactory} 的 invocationHooks 注册生效，
 * 典型用途为链路追踪、性能监控、日志埋点等切面逻辑。
 */
public interface InvocationHook {
    void beforeInvoke(Object target, Method method, Object[] args);

    void afterInvoke(Object target, Method method, Object result);
}
