package com.wly.job.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证摘要与 HMAC 签名工具测试（ADR-0006）。
 */
class Pbkdf2DigestTest {

    @Test
    void deriveIsDeterministicWithSameSalt() {
        String salt = Pbkdf2Digest.randomSalt();
        String digest1 = Pbkdf2Digest.derive("secret-token", salt, 120_000);
        String digest2 = Pbkdf2Digest.derive("secret-token", salt, 120_000);
        assertEquals(digest1, digest2);
    }

    @Test
    void deriveNewGeneratesDistinctSaltPerCall() {
        var d1 = Pbkdf2Digest.deriveNew("secret-token");
        var d2 = Pbkdf2Digest.deriveNew("secret-token");
        assertNotEquals(d1.salt(), d2.salt());
        assertNotEquals(d1.digest(), d2.digest());
        assertEquals(120_000, d1.iterations());
    }

    @Test
    void differentPlaintextYieldsDifferentDigest() {
        var d = Pbkdf2Digest.deriveNew("secret-token");
        String other = Pbkdf2Digest.derive("other-token", d.salt(), d.iterations());
        assertFalse(Pbkdf2Digest.constantTimeEquals(d.digest(), other));
    }

    @Test
    void constantTimeEqualsRejectsNull() {
        assertFalse(Pbkdf2Digest.constantTimeEquals(null, "x"));
        assertFalse(Pbkdf2Digest.constantTimeEquals("x", null));
    }

    @Test
    void deriveRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> Pbkdf2Digest.derive("", "c2FsdA==", 1000));
        assertThrows(IllegalArgumentException.class, () -> Pbkdf2Digest.derive("token", null, 1000));
        assertThrows(IllegalArgumentException.class, () -> Pbkdf2Digest.derive("token", "c2FsdA==", 0));
    }

    @Test
    void signAndVerifyRoundTrip() {
        String key = Pbkdf2Digest.deriveNew("secret-token").digest();
        String canonical = HmacSha256Signer.canonical("req-1", "job-a", "param", 1_700_000_000_000L, "req-1");
        String signature = HmacSha256Signer.sign(key, canonical);
        assertTrue(HmacSha256Signer.verify(key, canonical, signature));
    }

    @Test
    void verifyRejectsTamperedField() {
        String key = Pbkdf2Digest.deriveNew("secret-token").digest();
        String canonical = HmacSha256Signer.canonical("req-1", "job-a", "param", 1_700_000_000_000L, "req-1");
        String signature = HmacSha256Signer.sign(key, canonical);

        // 篡改任一字段（executeParam / timestamp / nonce）均导致签名不匹配
        String tamperedParam = HmacSha256Signer.canonical("req-1", "job-a", "param-evil", 1_700_000_000_000L, "req-1");
        assertFalse(HmacSha256Signer.verify(key, tamperedParam, signature));

        String tamperedTs = HmacSha256Signer.canonical("req-1", "job-a", "param", 1_700_000_000_001L, "req-1");
        assertFalse(HmacSha256Signer.verify(key, tamperedTs, signature));
    }

    @Test
    void verifyRejectsWrongKey() {
        String key = Pbkdf2Digest.deriveNew("secret-token").digest();
        String otherKey = Pbkdf2Digest.deriveNew("other-token").digest();
        String canonical = HmacSha256Signer.canonical("req-1", "job-a", "param", 1_700_000_000_000L, "req-1");
        String signature = HmacSha256Signer.sign(key, canonical);
        assertFalse(HmacSha256Signer.verify(otherKey, canonical, signature));
        assertFalse(HmacSha256Signer.verify(key, canonical, null));
    }

    @Test
    void canonicalIsStableWithNullFields() {
        String c1 = HmacSha256Signer.canonical("req-1", null, null, 100L, "req-1");
        String c2 = HmacSha256Signer.canonical("req-1", null, null, 100L, "req-1");
        assertEquals(c1, c2);
    }
}
