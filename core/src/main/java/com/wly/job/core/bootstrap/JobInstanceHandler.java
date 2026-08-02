package com.wly.job.core.bootstrap;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.InnerJobRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RPC服务器请求处理器
 */
@Slf4j
@ChannelHandler.Sharable
public class JobInstanceHandler extends SimpleChannelInboundHandler<ScheduleJobRequest> {

    private final ExecutorService executorService;

    private final InnerJobRegistry registry;

    public JobInstanceHandler(InnerJobRegistry registry) {
        // 使用可配置的线程池
        this.executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );

        this.registry = registry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ScheduleJobRequest request) throws Exception {
        log.debug("Receive schedule job request: {}", request.getRequestId());

        // 使用线程池处理请求，避免阻塞Netty的I/O线程
        executorService.submit(() -> {
            ScheduleJobResponse response = handleRequest(request);
            ctx.writeAndFlush(response).addListener(future -> {
                if (future.isSuccess()) {
                    log.debug("Send response success: {}", request.getRequestId());
                } else {
                    log.error("Send response fail: {}", request.getRequestId(), future.cause());
                }
            });
        });
    }

    private ScheduleJobResponse handleRequest(ScheduleJobRequest request) {
        try {
            InnerJob job = registry.get(request.getJobname());
            if (job == null) {
                return ScheduleJobResponse.builder()
                        .success(false)
                        .error("No such job: " + request.getJobname())
                        .requestId(request.getRequestId())
                        .build();
            }
            return job.execute(request);
        } catch (Exception e) {
            log.error("Schedule job request handle fail, requestId: {}", request.getRequestId(), e);
            return ScheduleJobResponse.builder()
                    .success(false)
                    .error(e.getMessage())
                    .requestId(request.getRequestId())
                    .build();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Schedule job fail: ", cause);
        ctx.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Schedule server connected: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Schedule server connection lost: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    /**
     * 关闭线程池：先温和关闭，超过宽限期再强制中断
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
