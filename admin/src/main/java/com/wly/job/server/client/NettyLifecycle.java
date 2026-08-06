package com.wly.job.server.client;

import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Admin 侧 Netty 客户端资源的生命周期管理：应用停止时按序释放连接、请求映射与回调线程池。
 */
@Component
@Slf4j
public class NettyLifecycle implements SmartLifecycle {

    private final ChannelManager channelManager;

    private final ScheduleRequestHandler requestHandler;

    private final ExecutorService callbackExecutor;

    private volatile boolean running = false;

    public NettyLifecycle(ChannelManager channelManager, ScheduleRequestHandler requestHandler,
                          ExecutorService callbackExecutor) {
        this.channelManager = channelManager;
        this.requestHandler = requestHandler;
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public void stop() {
        this.running = false;
        // 先断连接，再停请求映射/清理线程，最后等回调线程池排空
        channelManager.shutdown();
        requestHandler.shutdown();
        ThreadPoolUtils.shutdownGracefully(callbackExecutor, 3, TimeUnit.SECONDS);
        log.info("Netty client resources released.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 停机顺序编排（Spec §2.8）：低 phase 让连接/回调线程最后关闭，但仍在
     * ScheduleLeaderElector(MIN_VALUE) 之前——连接断开后再释放租约锁，
     * 避免"先断网、后停调度"时在途派发/回调被打断。
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 10;
    }
}
