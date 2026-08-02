package com.wly.job.server.client.callback;

/**
 * 调度 RPC 结果回调扩展点。
 * 实现类注册为 Spring Bean 后由 {@link ScheduleCallableRegistry} 自动收集；
 * 回调在 {@link com.wly.job.server.client.future.ScheduleFuture} 完成后异步执行，单个回调异常不影响其他回调。
 */
public interface ScheduleCallback {

    void onSuccess(ScheduleCallbackContext context, Object result);

    void onFailure(ScheduleCallbackContext context, Throwable cause);
}
