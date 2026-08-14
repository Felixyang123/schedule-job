package com.wly.job.core.bootstrap;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.security.HmacSha256Signer;
import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.core.security.RpcRequestAuthenticator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobInstanceHandler} RPC 鉴权单元测试（EmbeddedChannel 直连 handler，Spec 验收 7/8/9）：
 * 正确签名正常执行、篡改字段/重放/超时/版本不符均拒绝（unauthorized 并断开连接）。
 */
class JobInstanceHandlerTest {

    private static final String TOKEN = "secret-token";
    private static final int VERSION = 1;
    private static final int ITERATIONS = 120_000;

    private final AtomicInteger executedCount = new AtomicInteger();

    private EmbeddedChannel newChannel() {
        String salt = Pbkdf2Digest.randomSalt();
        String key = Pbkdf2Digest.derive(TOKEN, salt, ITERATIONS);
        return newChannel(salt, key);
    }

    private EmbeddedChannel newChannel(String salt, String key) {
        DefaultInnerJobRegistry registry = new DefaultInnerJobRegistry();
        registry.register(new InnerJob() {
            @Override
            public ScheduleJobResponse execute(ScheduleJobRequest request) {
                executedCount.incrementAndGet();
                return ScheduleJobResponse.builder()
                        .success(true).result("ok").requestId(request.getRequestId()).build();
            }

            @Override
            public String jobname() {
                return "echo";
            }
        });
        return new EmbeddedChannel(new JobInstanceHandler(registry,
                new RpcRequestAuthenticator(TOKEN, VERSION)));
    }

    private ScheduleJobRequest signedRequest(String requestId, String salt, String key) {
        long now = System.currentTimeMillis();
        ScheduleJobRequest request = ScheduleJobRequest.builder()
                .requestId(requestId)
                .jobname("echo")
                .executeParam("param")
                .credentialVersion(VERSION)
                .salt(salt)
                .iterations(ITERATIONS)
                .timestamp(now)
                .build();
        String canonical = HmacSha256Signer.canonical(
                request.getRequestId(), request.getJobname(), request.getExecuteParam(),
                request.getTimestamp(), request.getRequestId());
        request.setSignature(HmacSha256Signer.sign(key, canonical));
        return request;
    }

    @Test
    void validSignatureExecutesJob() throws Exception {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        EmbeddedChannel channel = newChannel(derivation.salt(), derivation.digest());
        try {
            channel.writeInbound(signedRequest("r1", derivation.salt(), derivation.digest()));

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response, "正确签名应返回执行结果");
            assertTrue(response.isSuccess());
            assertEquals("ok", response.getResult());
            assertTrue(channel.isActive(), "正确签名请求后连接应保持");
            assertEquals(1, executedCount.get(), "业务方法应恰好执行一次");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void tamperedRequestReturnsUnauthorizedAndClosesChannel() throws Exception {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        EmbeddedChannel channel = newChannel(derivation.salt(), derivation.digest());
        try {
            ScheduleJobRequest request = signedRequest("r2", derivation.salt(), derivation.digest());
            request.setExecuteParam("param-evil"); // 篡改字段：签名不再匹配
            channel.writeInbound(request);

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response, "篡改请求应返回失败响应");
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertTrue(channel.closeFuture().await(3, TimeUnit.SECONDS), "鉴权失败后连接应被关闭");
            assertEquals(0, executedCount.get(), "篡改请求不得触发业务方法");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void replayRequestRejectedBeforeBusinessExecution() throws Exception {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        EmbeddedChannel channel = newChannel(derivation.salt(), derivation.digest());
        try {
            ScheduleJobRequest first = signedRequest("r3", derivation.salt(), derivation.digest());
            channel.writeInbound(first);
            assertNotNull(awaitOutbound(channel));
            assertEquals(1, executedCount.get(), "首次请求应执行");

            // 同一 requestId 原样重放：去重集拦截，业务方法不再执行
            ScheduleJobRequest replay = signedRequest("r3", derivation.salt(), derivation.digest());
            channel.writeInbound(replay);

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertEquals(1, executedCount.get(), "重放请求不得再次触发业务方法");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void outOfWindowTimestampRejected() throws Exception {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        EmbeddedChannel channel = newChannel(derivation.salt(), derivation.digest());
        try {
            ScheduleJobRequest request = signedRequest("r4", derivation.salt(), derivation.digest());
            request.setTimestamp(System.currentTimeMillis() - 60_000L); // 超 ±30s 时间窗
            channel.writeInbound(request);

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertEquals(0, executedCount.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void versionMismatchRejected() throws Exception {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        EmbeddedChannel channel = newChannel(derivation.salt(), derivation.digest());
        try {
            ScheduleJobRequest request = signedRequest("r5", derivation.salt(), derivation.digest());
            request.setCredentialVersion(2); // 与本地版本不符
            channel.writeInbound(request);

            ScheduleJobResponse response = awaitOutbound(channel);
            assertNotNull(response);
            assertFalse(response.isSuccess());
            assertEquals("unauthorized", response.getError());
            assertEquals(0, executedCount.get());
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
