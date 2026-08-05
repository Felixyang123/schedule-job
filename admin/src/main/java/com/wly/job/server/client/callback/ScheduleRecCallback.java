package com.wly.job.server.client.callback;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.metrics.MetricsRegistry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置回调：执行结果回写 ScheduleRec；单次任务成功置 Finished、失败移出 in-flight 并写失败重试变更记录。
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Order(0)
public class ScheduleRecCallback implements ScheduleCallback {

    private final ScheduleRecQueue recQueue;

    private final JobRep jobRep;

    private final SingleRunTracker singleRunTracker;

    private final JobChangeRep changeRep;

    /** 可观测性指标封装（Spec §2.7） */
    private final MetricsRegistry metrics;

    /**
     * 执行成功回调（主节点专属，由 ScheduleFuture 回调线程触发）：
     * 单次任务先置 Finished 再移除 in-flight（带 type=SINGLE 守卫与 finished=0 条件）；
     * 置位成功后同事务写"单次完成"变更记录；最终把调度记录回写为 SUCCESS。
     */
    @Transactional
    @Override
    public void onSuccess(ScheduleCallbackContext context, Object result) {
        // 回调线程（Admin Netty I/O 完成 → 回调线程池）的 MDC 恒为空，装饰器捕获的空快照
        // 无法透传 requestId；此处直接以请求对象中的 requestId 写入 MDC，覆盖本方法内全部日志
        // （Spec 2026-08-06 §2.3，回调侧关联修复）。
        MDC.put("requestId", context.request().getRequestId());
        try {
            // 先置 Finished 再移除 in-flight：若先移除，对账线程可能在窗口内把任务重新入队造成重复执行
            if (context.singleRun() && context.jobId() != null) {
                boolean updated = jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                        .eq(Job::getId, context.jobId())
                        .eq(Job::getType, JobTypeEnum.SINGLE.getCode())
                        .eq(Job::getFinished, 0)
                        .set(Job::getFinished, 1));
                if (updated) {
                    changeRep.record(context.jobId(), JobChangeTypeEnum.FINISHED.getCode(),
                            "system", context.request().getRequestId(), context.request().getJobname());
                } else {
                    // 静默失败风险点：type=SINGLE 守卫命中 0 行（任务被改类型/重复完成），置 Finished 未生效
                    log.warn("mark single job finished fail, update 0 rows, jobId: {}, requestId: {}",
                            context.jobId(), context.request().getRequestId());
                }
            }
            singleRunTracker.remove(context.jobId());
            recQueue.markSuccess(context.request().getRequestId(), JSON.toJSONString(result));
            metrics.counter(MetricsRegistry.JOB_CALLBACK_SUCCESS).increment();
            log.debug("callback success, requestId: {}, jobId: {} -> SUCCESS",
                    context.request().getRequestId(), context.jobId());
        } finally {
            MDC.remove("requestId");
        }
    }

    /**
     * 执行失败/超时回调：释放 in-flight，单次任务写"失败重试"变更记录（由变更源 ≤1s 重新入队，
     * 覆盖所有释放 in-flight 的路径，不得在进程内加捷径）；调度记录回写为 FAIL 并记录异常信息。
     */
    @Override
    public void onFailure(ScheduleCallbackContext context, Throwable cause) {
        MDC.put("requestId", context.request().getRequestId());
        try {
            singleRunTracker.remove(context.jobId());
            if (context.singleRun() && context.jobId() != null) {
                changeRep.record(context.jobId(), JobChangeTypeEnum.REQUEUE.getCode(),
                        "system", context.request().getRequestId(), context.request().getJobname());
            }
            recQueue.markFail(context.request().getRequestId(), cause == null ? null : cause.getMessage());
            metrics.counter(MetricsRegistry.JOB_CALLBACK_FAILURE).increment();
            log.warn("callback fail, requestId: {}, jobId: {} -> FAIL, job: {}",
                    context.request().getRequestId(), context.jobId(), context.request().getJobname(), cause);
        } finally {
            MDC.remove("requestId");
        }
    }
}
