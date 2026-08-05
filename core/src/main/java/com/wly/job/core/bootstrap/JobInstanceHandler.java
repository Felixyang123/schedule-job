package com.wly.job.core.bootstrap;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.InnerJobRegistry;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RPC服务器请求处理器
 *
 * <p>Admin 调度 RPC 入站处理器：解码后的 {@link ScheduleJobRequest} 到达后，立即提交给
 * 独立业务线程池执行，严禁在 Netty I/O 线程上做反射调用等耗时操作（@Sharable 保证单实例
 * 可被多 Channel 共享）。执行路径：按 jobname 从本地任务注册表 {@link InnerJobRegistry}
 * 取 {@link InnerJob} 并执行，结果封装为 {@link ScheduleJobResponse} 回写请求方。
 *
 * <p>RPC 鉴权：构造时注入 {@code expectedToken}（对应 Worker 配置 {@code schedule-job.accessToken}）。
 * 未配置（空/null）时跳过校验，兼容旧部署；配置后请求携带的 {@code token} 必须与其常量时间比较一致，
 * 否则返回 {@code unauthorized} 失败响应并关闭连接（防伪造请求触发任意已注册任务）。
 *
 * <p>线程模型：构造时按 CPU 核数 * 2 创建固定线程池；{@link #shutdown} 采用
 * 「温和关闭 + 1s 宽限 + 强制中断」的三段式优雅停机。
 */
@Slf4j
@ChannelHandler.Sharable
public class JobInstanceHandler extends SimpleChannelInboundHandler<ScheduleJobRequest> {

    private static final String UNAUTHORIZED_MESSAGE = "unauthorized";

    private final ExecutorService executorService;

    private final InnerJobRegistry registry;

    /**
     * 期望的 RPC 鉴权令牌（{@code schedule-job.accessToken}）；空/null 表示不校验（兼容旧部署）
     */
    private final String expectedToken;

    public JobInstanceHandler(InnerJobRegistry registry, String expectedToken) {
        // 使用可配置的线程池
        this.executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );

        this.registry = registry;
        this.expectedToken = expectedToken;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ScheduleJobRequest request) throws Exception {
        log.debug("Receive schedule job request: {}", request.getRequestId());

        // 使用线程池处理请求，避免阻塞Netty的I/O线程
        executorService.submit(() -> {
            ScheduleJobResponse response = handleRequest(ctx, request);
            if (response != null) {
                ctx.writeAndFlush(response).addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("Send response success: {}", request.getRequestId());
                    } else {
                        log.error("Send response fail: {}", request.getRequestId(), future.cause());
                    }
                });
            }
        });
    }

    /**
     * 校验并执行调度请求。
     * <p>鉴权失败时返回 {@code null} 并负责「写回 unauthorized 响应 → 写入完成后关闭连接」
     * （先写回再关闭，确保请求方收到失败原因）；其余路径返回待写回的响应。
     */
    private ScheduleJobResponse handleRequest(ChannelHandlerContext ctx, ScheduleJobRequest request) {
        if (!tokenValid(request)) {
            log.warn("Unauthorized schedule job request, remote={}, jobname={}, requestId={}",
                    ctx.channel().remoteAddress(), request.getJobname(), request.getRequestId());
            ScheduleJobResponse response = ScheduleJobResponse.builder()
                    .success(false)
                    .error(UNAUTHORIZED_MESSAGE)
                    .requestId(request.getRequestId())
                    .build();
            ctx.writeAndFlush(response).addListener(future -> ctx.close());
            return null;
        }
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

    /**
     * RPC 鉴权校验：{@code expectedToken} 未配置（空/null）时跳过校验（兼容旧部署）；
     * 配置后请求 token 必须与其一致（常量时间比较，防时序侧信道）。
     */
    private boolean tokenValid(ScheduleJobRequest request) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        String presented = request.getToken();
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
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
