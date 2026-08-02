package com.wly.job.server.client;

import com.wly.job.server.client.handler.ScheduleRequestHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Admin 侧 Netty 客户端资源的生命周期管理：应用停止时释放连接与回调线程池。
 */
@Component
@Slf4j
public class NettyLifecycle implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
        ChannelManager.shutdown();
        ScheduleRequestHandler.shutdown();
        log.info("Netty client resources released.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
