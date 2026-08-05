package com.wly.job.core.bootstrap;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobInstanceHandler} RPC 鉴权单元测试（EmbeddedChannel 直连 handler）：
 * 正确 token 正常执行、错误 token 返回 unauthorized 并断开连接、未配置 expectedToken 跳过校验。
 */
class JobInstanceHandlerTest {

    private static final String TOKEN = "secret-token";

    private EmbeddedChannel newChannel(String expectedToken) {
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
        return new EmbeddedChannel(new JobInstanceHandler(registry, expectedToken));
    }

    @Test
    void correctTokenExecutesJob() throws Exception {
        EmbeddedChannel channel = newChannel(TOKEN);
        try {
            channel.writeInbound(ScheduleJobRequest.builder().requestId("r1").jobname("echo").token(TOKEN).build());

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response, "正确 token 应返回执行结果");
            assertTrue(response.isSuccess());
            assertEquals("ok", response.getResult());
            assertTrue(channel.isActive(), "正确 token 请求后连接应保持");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void wrongTokenReturnsUnauthorizedAndClosesChannel() throws Exception {
        EmbeddedChannel channel = newChannel(TOKEN);
        try {
            channel.writeInbound(ScheduleJobRequest.builder().requestId("r2").jobname("echo").token("wrong-token").build());

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response, "错误 token 应返回失败响应");
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertTrue(channel.closeFuture().await(3, TimeUnit.SECONDS), "错误 token 请求后连接应被关闭");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingTokenRejectedWhenConfigured() throws Exception {
        // 旧 Admin 派发请求不带 token（反序列化为 null），配置了 expectedToken 的 Worker 应拒绝
        EmbeddedChannel channel = newChannel(TOKEN);
        try {
            channel.writeInbound(ScheduleJobRequest.builder().requestId("r3").jobname("echo").build());

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertTrue(channel.closeFuture().await(3, TimeUnit.SECONDS));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void blankExpectedTokenSkipsValidation() throws Exception {
        // 未配置 expectedToken（空串）：不带 token 的请求照常执行（兼容旧部署）
        EmbeddedChannel channel = newChannel("");
        try {
            channel.writeInbound(ScheduleJobRequest.builder().requestId("r4").jobname("echo").build());

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            assertEquals("ok", response.getResult());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void nullExpectedTokenSkipsValidation() throws Exception {
        // 未配置 expectedToken（null）：任意 token 请求均执行
        EmbeddedChannel channel = newChannel(null);
        try {
            channel.writeInbound(ScheduleJobRequest.builder().requestId("r5").jobname("echo").token("whatever").build());

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertTrue(response.isSuccess());
            assertEquals("ok", response.getResult());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * 轮询读取出站响应：handler 内部在业务线程池异步执行，写回需要时间。
     */
    private ScheduleJobResponse awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        ScheduleJobResponse response;
        while ((response = channel.readOutbound()) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return response;
    }
}
