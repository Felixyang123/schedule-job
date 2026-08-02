package com.wly.job.server.client;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.codec.JsonDecoder;
import com.wly.job.common.codec.JsonEncoder;
import com.wly.job.common.exception.ScheduleException;
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

public class ChannelManager {
    private static final ConcurrentMap<String, Channel> CHANNEL_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, ChannelWrapper> CHANNEL_WRAPPER_MAP = new ConcurrentHashMap<>();

    private static final EventLoopGroup EVENTLOOPGROUP = new NioEventLoopGroup();

    private static final ScheduleClientHandler HANDLER = new ScheduleClientHandler();

    public static Channel getChannel(String host, Integer port) {
        String key = host + ":" + port;
        return CHANNEL_MAP.computeIfAbsent(key, k -> {
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

            try {
                Channel channel = bootstrap.connect(host, port).sync().channel();
                CHANNEL_WRAPPER_MAP.put(channel.id().asLongText(), new ChannelWrapper(channel, key));
                return channel;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScheduleException("Connect schedule instance fail: " + key, e);
            }
        });
    }

    public static void removeChannel(Channel channel) {
        ChannelWrapper channelWrapper = CHANNEL_WRAPPER_MAP.remove(channel.id().asLongText());
        if (channelWrapper != null) {
            channelWrapper.getChannel().close();
            CHANNEL_MAP.remove(channelWrapper.getChannelKey());
        }
    }

    public static void shutdown() {
        CHANNEL_MAP.values().forEach(Channel::close);
        CHANNEL_MAP.clear();
        CHANNEL_WRAPPER_MAP.clear();
        EVENTLOOPGROUP.shutdownGracefully();
    }

    @Data
    @AllArgsConstructor
    public static class ChannelWrapper {
        private Channel channel;

        private String channelKey;
    }
}
