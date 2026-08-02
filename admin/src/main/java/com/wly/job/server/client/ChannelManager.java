package com.wly.job.server.client;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.codec.JsonDecoder;
import com.wly.job.common.codec.JsonEncoder;
import com.wly.job.server.client.handler.ScheduleClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;

public class ChannelManager {
    private static final ConcurrentMap<String, CompletableFuture<Channel>> CHANNEL_FUTURE_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, ChannelWrapper> CHANNEL_WRAPPER_MAP = new ConcurrentHashMap<>();

    private static final EventLoopGroup EVENTLOOPGROUP = new NioEventLoopGroup();

    private static final ScheduleClientHandler HANDLER = new ScheduleClientHandler();

    public static CompletableFuture<Channel> getChannelAsync(String host, Integer port) {
        String key = host + ":" + port;
        return CHANNEL_FUTURE_MAP.computeIfAbsent(key, k -> {
            CompletableFuture<Channel> future = new CompletableFuture<>();
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(EVENTLOOPGROUP)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new JsonDecoder(ScheduleJobResponse.class));
                            pipeline.addLast(new JsonEncoder(ScheduleJobRequest.class));
                            pipeline.addLast(HANDLER);
                        }
                    })
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    Channel channel = f.channel();
                    CHANNEL_WRAPPER_MAP.put(channel.id().asLongText(), new ChannelWrapper(channel, key));
                    future.complete(channel);
                } else {
                    CHANNEL_FUTURE_MAP.remove(key, future);
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        });
    }

    public static void removeChannel(Channel channel) {
        ChannelWrapper channelWrapper = CHANNEL_WRAPPER_MAP.remove(channel.id().asLongText());
        if (channelWrapper != null) {
            channelWrapper.getChannel().close();
            CHANNEL_FUTURE_MAP.remove(channelWrapper.getChannelKey());
        }
    }

    public static void shutdown() {
        CHANNEL_WRAPPER_MAP.values().forEach(wrapper -> wrapper.getChannel().close());
        CHANNEL_WRAPPER_MAP.clear();
        CHANNEL_FUTURE_MAP.clear();
        EVENTLOOPGROUP.shutdownGracefully();
    }

    @Data
    @AllArgsConstructor
    public static class ChannelWrapper {
        private Channel channel;

        private String channelKey;
    }
}
