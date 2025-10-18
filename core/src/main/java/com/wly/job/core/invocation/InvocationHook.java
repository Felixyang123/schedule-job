package com.wly.job.core.invocation;

import java.lang.reflect.Method;

public interface InvocationHook {
    void beforeInvoke(Object target, Method method, Object[] args);

    void afterInvoke(Object target, Method method, Object result);
}
