package com.wly.job.server.client.callback;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 内置回调：执行结果回写 ScheduleRec；单次任务成功置 Finished、失败移出 in-flight。
 */
@Component
@RequiredArgsConstructor
@Order(0)
public class ScheduleRecCallback implements ScheduleCallback {

    private final ScheduleRecQueue recQueue;

    private final JobRep jobRep;

    private final SingleRunTracker singleRunTracker;

    // FIXME 先删除inflight再更新状态有竞态条件：如果定时任务此时执行检查没有inflight则入队列重复执行
    @Override
    public void onSuccess(ScheduleCallbackContext context, Object result) {
        // 先置 Finished 再移除 in-flight：若先移除，对账线程可能在窗口内把任务重新入队造成重复执行
        if (context.singleRun() && context.jobId() != null) {
            jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                    .eq(Job::getId, context.jobId())
                    .eq(Job::getFinished, 0)
                    .set(Job::getFinished, 1));
        }
        singleRunTracker.remove(context.jobId());
        recQueue.markSuccess(context.request().getRequestId(), JSON.toJSONString(result));
    }

    @Override
    public void onFailure(ScheduleCallbackContext context, Throwable cause) {
        singleRunTracker.remove(context.jobId());
        recQueue.markFail(context.request().getRequestId(), cause == null ? null : cause.getMessage());
    }
}
