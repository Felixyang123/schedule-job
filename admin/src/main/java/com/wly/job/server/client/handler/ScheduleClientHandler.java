package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.ChannelManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin 侧 Netty 客户端入站处理器（@Sharable 单例，处理执行器回包）：
 * 收到响应即完成对应 requestId 的 Future（回调由 ScheduleFuture 异步派发，不在 Netty I/O 线程执行）；
 * 连接断开时把该连接上所有未完成请求标记为失败并移除连接缓存。
 * 由 {@link ChannelManager} 构造并挂入 pipeline，依赖通过构造注入（非 Spring Bean）。
 */
@Slf4j
@ChannelHandler.Sharable
public class ScheduleClientHandler extends SimpleChannelInboundHandler<ScheduleJobResponse> {

    private final ChannelManager channelManager;

    private final ScheduleRequestHandler requestHandler;

    public ScheduleClientHandler(ChannelManager channelManager, ScheduleRequestHandler requestHandler) {
        this.channelManager = channelManager;
        this.requestHandler = requestHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ScheduleJobResponse response) throws Exception {
        // 收到响应，完成对应的Future
        log.debug("Receive scheduleJobResponse: {}", response);
        requestHandler.complete(response.getRequestId(), response);
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
        requestHandler.cleanupAllRequests(ctx.channel());
        channelManager.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }
}
