package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.ChannelManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ScheduleClientHandler extends SimpleChannelInboundHandler<ScheduleJobResponse> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ScheduleJobResponse response) throws Exception {
        // 收到响应，完成对应的Future
        log.debug("Receive scheduleJobResponse: {}", response);
        ScheduleRequestHandler.complete(response.getRequestId(), response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Schedule fail:", cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Schedule instance connection lost");
        // 连接断开时，将所有未完成的请求标记为失败
        ScheduleRequestHandler.cleanupAllRequests(ctx.channel());
        ChannelManager.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }
}