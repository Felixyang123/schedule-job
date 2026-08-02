package com.wly.job.core.bootstrap;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.codec.JsonDecoder;
import com.wly.job.common.codec.JsonEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobBootstrap {
    private final int port;
    private final JobInstanceHandler serverHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile boolean started;

    public JobBootstrap(int port, JobInstanceHandler serverHandler) {
        this.port = port;
        this.serverHandler = serverHandler;
    }

    public void start() {
        if (started) {
            return;
        }
        this.started = true;
        Thread bootstrapThread = new Thread(() -> {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            try {
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                                pipeline.addLast(new LengthFieldPrepender(4));
                                pipeline.addLast(new JsonDecoder(ScheduleJobRequest.class));
                                pipeline.addLast(new JsonEncoder(ScheduleJobResponse.class));
                                pipeline.addLast(serverHandler);
                            }
                        })
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                ChannelFuture future = bootstrap.bind(port).sync();
                log.info("Schedule job instance started on port {}", port);
                future.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                log.error("Schedule job instance start fail on port {}", port, e);
            } finally {
                shutdown();
            }
        });
        bootstrapThread.setName("Schedule job instance thread-" + port);
        bootstrapThread.start();
    }

    public void shutdown() {
        if (serverHandler != null) {
            serverHandler.shutdown();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("RPC服务器已关闭");
    }
}
