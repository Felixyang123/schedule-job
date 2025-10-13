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
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Slf4j
public record ScheduleJobClient(ScheduleProps props, ScheduleRecRep recRep) {

    public void send(ScheduleJobRequest request, JobInstance instance) {
        Channel channel = ChannelManager.getChannel(instance.getHost(), instance.getPort());
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(props.getReqTimeout(), channel);
        String requestId = request.getRequestId();
        future.addCallable(new ScheduleResultCallable(recRep, requestId));
        ScheduleRequestHandler.put(requestId, future);
        channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Send request fail: ", f.cause());
                // 发送失败，立即完成Future
                ScheduleRequestHandler.complete(requestId, ScheduleJobResponse.builder()
                        .requestId(requestId).success(false).error("Send request fail").build());
            }
        });
    }

    public record ScheduleResultCallable(ScheduleRecRep recRep, String requestId) implements ScheduleCallable {
        @Override
        public void onSuccess(Object result) {
            ScheduleRec rec = ScheduleRec.builder().status(ScheduleRec.SUCCESS).executeResult(JSON.toJSONString(result)).completeTime(new Date()).build();
            recRep.update(rec,Wrappers.<ScheduleRec>lambdaUpdate().eq(ScheduleRec::getRequestId, requestId));
        }

        @Override
        public void onFailure(Throwable throwable) {
            ScheduleRec rec = ScheduleRec.builder().status(ScheduleRec.FAIL).executeResult(throwable.getMessage()).completeTime(new Date()).build();
            recRep.update(rec,Wrappers.<ScheduleRec>lambdaUpdate().eq(ScheduleRec::getRequestId, requestId));
        }
    }
}
