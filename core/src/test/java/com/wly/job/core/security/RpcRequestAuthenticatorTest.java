package com.wly.job.core.security;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.security.HmacSha256Signer;
import com.wly.job.common.security.Pbkdf2Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker 侧 RPC 验签器测试（Spec 2026-08-11 验收 7/8/9/10）。
 */
class RpcRequestAuthenticatorTest {

    private static final String PLAIN_TOKEN = "defaultToken";
    private static final int VERSION = 1;
    private static final int ITERATIONS = 120_000;

    private RpcRequestAuthenticator authenticator;

    private String salt;

    private String key;

    @BeforeEach
    void setUp() {
        authenticator = new RpcRequestAuthenticator(PLAIN_TOKEN, VERSION);
        var derivation = Pbkdf2Digest.deriveNew(PLAIN_TOKEN);
        salt = derivation.salt();
        key = derivation.digest();
    }

    private ScheduleJobRequest validRequest() {
        long now = System.currentTimeMillis();
        ScheduleJobRequest request = ScheduleJobRequest.builder()
                .requestId("req-" + now)
                .jobname("demoJob")
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
    void validRequestPassesPreCheckAndAuthenticate() {
        ScheduleJobRequest request = validRequest();
        assertEquals(RpcRequestAuthenticator.AuthResult.OK, authenticator.preCheck(request));
        assertTrue(authenticator.authenticate(request));
    }

    @Test
    void outOfWindowTimestampRejectedInPreCheck() {
        ScheduleJobRequest request = validRequest();
        request.setTimestamp(System.currentTimeMillis() - 60_000L);
        assertEquals(RpcRequestAuthenticator.AuthResult.TIMESTAMP_EXPIRED, authenticator.preCheck(request));
    }

    @Test
    void replayRequestRejectedInPreCheckWithoutBusinessCall() {
        ScheduleJobRequest first = validRequest();
        ScheduleJobRequest replay = validRequest();
        replay.setRequestId(first.getRequestId());

        assertEquals(RpcRequestAuthenticator.AuthResult.OK, authenticator.preCheck(first));
        assertEquals(RpcRequestAuthenticator.AuthResult.REPLAY, authenticator.preCheck(replay));
    }

    @Test
    void versionMismatchRejectedInPreCheck() {
        ScheduleJobRequest request = validRequest();
        request.setCredentialVersion(2);
        assertEquals(RpcRequestAuthenticator.AuthResult.VERSION_MISMATCH, authenticator.preCheck(request));
    }

    @Test
    void tamperedSignatureRejectedInAuthenticate() {
        ScheduleJobRequest request = validRequest();
        request.setExecuteParam("param-evil");
        assertEquals(RpcRequestAuthenticator.AuthResult.OK, authenticator.preCheck(request));
        assertFalse(authenticator.authenticate(request));
    }

    @Test
    void derivedKeyCachedAfterFirstAuthenticate() {
        ScheduleJobRequest first = validRequest();
        assertEquals(RpcRequestAuthenticator.AuthResult.OK, authenticator.preCheck(first));
        assertTrue(authenticator.authenticate(first));
        assertEquals(1, authenticator.derivedKeyCacheSize());

        ScheduleJobRequest second = validRequest();
        assertEquals(RpcRequestAuthenticator.AuthResult.OK, authenticator.preCheck(second));
        assertTrue(authenticator.authenticate(second));
        // 同版本同 salt/iterations：复用缓存，不重复派生
        assertEquals(1, authenticator.derivedKeyCacheSize());
    }

    @Test
    void missingSignatureRejectedInPreCheck() {
        ScheduleJobRequest request = validRequest();
        request.setSignature(null);
        assertEquals(RpcRequestAuthenticator.AuthResult.MISSING_SIGNATURE, authenticator.preCheck(request));
    }
}
