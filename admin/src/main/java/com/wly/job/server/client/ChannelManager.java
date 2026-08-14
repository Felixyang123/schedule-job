package com.wly.job.server.client;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.codec.JsonDecoder;
import com.wly.job.common.codec.JsonEncoder;
import com.wly.job.server.client.handler.ScheduleClientHandler;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;

/**
 * Admin 到执行器（Worker）的 Netty 连接管理（Spring Bean）：
 * 按 host:port 复用连接，连接建立用 {@link CompletableFuture} 承载异步结果并缓存，
 * 避免并发发送时重复建连；同时维护 channelId -> ChannelWrapper 的映射，
 * 用于连接断开时统一清理与释放。
 * 所有资源均为实例级（EventLoopGroup/Handler），应用停止时由 NettyLifecycle 调用 {@link #shutdown()} 释放。
 */
@Component
public class ChannelManager {

    private final ConcurrentMap<String, CompletableFuture<Channel>> channelFutureMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ChannelWrapper> channelWrapperMap = new ConcurrentHashMap<>();

    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    private final ScheduleClientHandler handler;

    public ChannelManager(ScheduleRequestHandler requestHandler) {
        this.handler = new ScheduleClientHandler(this, requestHandler);
    }

    /**
     * 异步获取（或建立）到目标执行器的连接：
     * computeIfAbsent 保证同一 host:port 并发请求共享同一个建连 Future；
     * 建连失败时移除缓存项，允许后续请求重试。
     */
    public CompletableFuture<Channel> getChannelAsync(String host, Integer port) {
        String key = host + ":" + port;
        return channelFutureMap.computeIfAbsent(key, k -> {
            // Netty ChannelFuture 不能直接作为 CompletionStage；用 CompletableFuture 适配并发共享。
            // connect listener 负责在成功/失败两条路径完成 future，失败时同时摘除缓存以允许重试。
            CompletableFuture<Channel> future = new CompletableFuture<>();
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new JsonDecoder(ScheduleJobResponse.class));
                            pipeline.addLast(new JsonEncoder(ScheduleJobRequest.class));
                            pipeline.addLast(handler);
                        }
                    })
                    .option(ChannelOption.TCP_NODELAY, true)
                    // 每个在线 Worker 仅复用一个长连接（channelFutureMap 按 host:port 去重）；
                    // 调度 RPC 高频、双向异步，连接复用比逐请求建连显著降低握手开销。
                    //
                    // 有意不设连接数上限、不做空闲回收，理由如下：
                    // 1. 连接数 = 在线 Worker 数，由集群规模决定，不会无界增长；
                    // 2. 空闲不等于死亡——低频任务（如每天/每月一次）的连接可能长时间无流量，
                    //    这是正常业务态而非资源泄漏；若按闲置时长强制回收，会导致这批任务恰好
                    //    在同一 cron 触发点（如 0 点/9 点）集中重新建连，人为制造建连风暴，
                    //    伤害真正需要复用长连接的场景，却省不下多少资源（闲置连接仅占一个 fd）；
                    // 3. 真正的死连接（Worker 崩溃/网络分区）由两条路径清理：
                    //    a) TCP 层 channelInactive（SO_KEEPALIVE 探测）触发 removeChannel；
                    //    b) 写包失败时主动 removeChannel（见 ScheduleJobClient.send 写包失败分支），
                    //       覆盖 channelInactive 探测延迟窗口内的半开连接。
                    // 因此资源规模与活跃 Worker 数线性相关，无需额外的容量/闲置控制。
                    .option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    Channel channel = f.channel();
                    channelWrapperMap.put(channel.id().asLongText(), new ChannelWrapper(channel, key));
                    future.complete(channel);
                } else {
                    // 建连失败：移除缓存让下次调用重试，并以异常通知等待方
                    channelFutureMap.remove(key, future);
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        });
    }

    /**
     * 移除指定连接：关闭 channel、清理 host:port 连接缓存（连接断开时由 handler 调用）。
     */
    public void removeChannel(Channel channel) {
        ChannelWrapper channelWrapper = channelWrapperMap.remove(channel.id().asLongText());
        if (channelWrapper != null) {
            channelWrapper.getChannel().close();
            channelFutureMap.remove(channelWrapper.getChannelKey());
        }
    }

    /** 释放全部连接与 EventLoopGroup（应用停止时由 NettyLifecycle 调用） */
    public void shutdown() {
        channelWrapperMap.values().forEach(wrapper -> wrapper.getChannel().close());
        channelWrapperMap.clear();
        channelFutureMap.clear();
        eventLoopGroup.shutdownGracefully();
    }

    @Data
    @AllArgsConstructor
    public static class ChannelWrapper {
        private Channel channel;

        private String channelKey;
    }
}
