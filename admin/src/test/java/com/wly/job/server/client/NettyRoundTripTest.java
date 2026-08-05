package com.wly.job.server.client;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.bootstrap.JobBootstrap;
import com.wly.job.core.bootstrap.JobInstanceHandler;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.server.client.future.ScheduleFuture;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NettyRoundTripTest {

    private static final String TOKEN = "test-token";

    private ChannelManager channelManager;
    private ScheduleRequestHandler requestHandler;
    private ExecutorService executor;

    private JobBootstrap bootstrap;
    private int port;
    private Channel channel;

    @BeforeAll
    void initClient() {
        requestHandler = new ScheduleRequestHandler();
        channelManager = new ChannelManager(requestHandler);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-callback");
            thread.setDaemon(true);
            return thread;
        });
    }

    @BeforeEach
    void startWorker() throws Exception {
        startWorker(TOKEN);
    }

    private void startWorker(String expectedToken) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DefaultInnerJobRegistry registry = new DefaultInnerJobRegistry();
        registry.register(new InnerJob() {
            @Override
            public ScheduleJobResponse execute(ScheduleJobRequest request) {
                return ScheduleJobResponse.builder()
                        .success(true).result("ok").requestId(request.getRequestId()).build();
            }

            @Override
            public String jobname() {
                return "echo";
            }
        });
        bootstrap = new JobBootstrap(port, new JobInstanceHandler(registry, expectedToken));
        bootstrap.start();

        channel = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (channel == null && System.currentTimeMillis() < deadline) {
            try {
                channel = channelManager.getChannelAsync("127.0.0.1", port).get(200, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                Thread.sleep(50);
            }
        }
        assertNotNull(channel, "worker 未在 5s 内就绪");
    }

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isActive()) {
            channelManager.removeChannel(channel);
        }
        channel = null;
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @AfterAll
    void releaseResources() {
        channelManager.shutdown();
        requestHandler.shutdown();
        executor.shutdownNow();
    }

    @Test
    void successRoundTrip() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel, executor);
        requestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("echo").token(TOKEN).build()).sync();

        ScheduleJobResponse response = future.get(3, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());
        assertEquals(requestId, response.getRequestId());
        assertEquals("ok", response.getResult());
    }

    @Test
    void unknownJobReturnsFailure() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel, executor);
        requestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("missing").token(TOKEN).build()).sync();

        ScheduleException ex = assertThrows(ScheduleException.class, () -> future.get(3, TimeUnit.SECONDS));
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root.getMessage().contains("No such job"));
    }

    @Test
    void wrongTokenRejectedAndChannelClosed() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel, executor);
        requestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("echo").token("wrong-token").build()).sync();

        // 错误 token：收到 unauthorized 失败响应（写回完成后连接才关闭）
        ScheduleException ex = assertThrows(ScheduleException.class, () -> future.get(3, TimeUnit.SECONDS));
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root.getMessage().contains("unauthorized"));

        // Worker 侧应主动断开连接（防暴力尝试）
        long deadline = System.currentTimeMillis() + 3000;
        while (channel.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(channel.isActive(), "错误 token 请求后连接应被 Worker 断开");
    }

    @Test
    void missingTokenRejectedWhenConfigured() throws Exception {
        // 旧 Admin 派发无 token 请求时同样拒绝（字段反序列化为 null）
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel, executor);
        requestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("echo").build()).sync();

        ScheduleException ex = assertThrows(ScheduleException.class, () -> future.get(3, TimeUnit.SECONDS));
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(root.getMessage().contains("unauthorized"));
    }

    @Test
    void blankExpectedTokenSkipsValidation() throws Exception {
        // 未配置 expectedToken 时跳过校验：无 token 请求照常执行（兼容旧部署）
        bootstrap.shutdown();
        startWorker("");

        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel, executor);
        requestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("echo").build()).sync();

        ScheduleJobResponse response = future.get(3, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());
        assertEquals("ok", response.getResult());
    }
}
