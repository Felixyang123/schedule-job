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
