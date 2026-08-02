package com.wly.job.server.client;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.future.ScheduleCallable;
import com.wly.job.server.client.future.ScheduleFuture;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public record ScheduleJobClient(ScheduleProps props, ScheduleRecQueue recQueue, JobRep jobRep,
                                SingleRunTracker tracker) {

    public void send(ScheduleJobRequest request, JobInstance instance, Long jobId, boolean singleRun) {
        Channel channel = ChannelManager.getChannel(instance.getHost(), instance.getPort());
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(props.getReqTimeout(), channel);
        String requestId = request.getRequestId();
        future.addCallable(new ScheduleResultCallable(recQueue, jobRep, tracker, requestId, jobId, singleRun));
        ScheduleRequestHandler.put(requestId, future, props.getReqTimeout());
        channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Send request fail: ", f.cause());
                // 发送失败，立即完成 Future 并触发失败回调
                ScheduleRequestHandler.complete(requestId, ScheduleJobResponse.builder()
                        .requestId(requestId).success(false).error("Send request fail").build());
            }
        });
    }

    public record ScheduleResultCallable(ScheduleRecQueue recQueue, JobRep jobRep, SingleRunTracker tracker,
                                         String requestId, Long jobId, boolean singleRun) implements ScheduleCallable {
        @Override
        public void onSuccess(Object result) {
            tracker.remove(jobId);
            recQueue.markSuccess(requestId, JSON.toJSONString(result));
            // 单次任务成功 -> Finished 终态（与管理态 status 解耦），见 ADR-0003
            if (singleRun && jobId != null) {
                jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                        .eq(Job::getId, jobId)
                        .eq(Job::getFinished, 0)
                        .set(Job::getFinished, 1));
            }
        }

        @Override
        public void onFailure(Throwable throwable) {
            // 失败移出 in-flight，由定时扫描按 Cron 自然重试
            tracker.remove(jobId);
            recQueue.markFail(requestId, throwable == null ? null : throwable.getMessage());
        }
    }
}
