package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.server.credential.CredentialInfo;
import com.wly.job.server.credential.CredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OpenApiTokenInterceptor} 单元测试（ADR-0006 / Spec 验收 2）：
 * 缺 Header 身份 / token 错误 / 身份不存在 / 正确身份+token，四种情况。
 */
class OpenApiTokenInterceptorTest {

    private static final String TOKEN = "secret-token";
    private static final String APP = "payment";
    private static final String ENV = "prod";

    private CredentialService credentialService;
    private OpenApiTokenInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        var derivation = Pbkdf2Digest.deriveNew(TOKEN);
        CredentialInfo.VersionInfo active = new CredentialInfo.VersionInfo(
                1, derivation.digest(), derivation.salt(), derivation.iterations(),
                new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(90)));
        CredentialInfo info = new CredentialInfo(APP, ENV, active, null);

        credentialService = mock(CredentialService.class);
        when(credentialService.lookup(APP, ENV)).thenReturn(Optional.of(info));
        when(credentialService.lookupForced(APP, ENV)).thenReturn(Optional.of(info));

        interceptor = new OpenApiTokenInterceptor(credentialService, new ObjectMapper());
        request = new MockHttpServletRequest();
        request.setRequestURI("/open/job/register");
        request.setRemoteAddr("192.168.1.10");
        response = new MockHttpServletResponse();
    }

    private void addIdentityHeaders() {
        request.addHeader("X-Job-Group", APP);
        request.addHeader("X-Job-Env", ENV);
    }

    // ---------- 正确身份 + 正确 token：放行 ----------

    @Test
    void passesWithCorrectIdentityAndBearerToken() throws Exception {
        addIdentityHeaders();
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());

        OpenApiAuthContext auth = (OpenApiAuthContext) request.getAttribute(OpenApiAuthContext.REQUEST_ATTRIBUTE);
        assertEquals(APP, auth.applicationName());
        assertEquals(ENV, auth.env());
        assertEquals(1, auth.credentialVersion());
    }

    @Test
    void passesWithCaseInsensitiveBearerPrefix() throws Exception {
        addIdentityHeaders();
        request.addHeader("Authorization", "bEaReR " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());
    }

    // ---------- 缺 Header 身份：拒绝 ----------

    @Test
    void rejectsWhenIdentityHeadersMissing() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenEnvHeaderMissing() throws Exception {
        request.addHeader("X-Job-Group", APP);
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    // ---------- 身份不存在：拒绝 ----------

    @Test
    void rejectsWhenIdentityUnknown() throws Exception {
        addIdentityHeaders();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(credentialService.lookup(anyString(), anyString())).thenReturn(Optional.empty());

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    // ---------- 错误 token：拒绝 ----------

    @Test
    void rejectsWithWrongToken() throws Exception {
        addIdentityHeaders();
        request.addHeader("Authorization", "Bearer wrong-token");

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenAuthorizationHeaderMissing() throws Exception {
        addIdentityHeaders();

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenBearerPrefixOnly() throws Exception {
        addIdentityHeaders();
        request.addHeader("Authorization", "Bearer");

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    private void assertUnauthorizedResponse() throws Exception {
        assertEquals(401, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.includes(
                MediaType.parseMediaType(response.getContentType())));
        Result<?> body = new ObjectMapper().readValue(response.getContentAsString(), Result.class);
        assertFalse(body.getSuccess());
        assertEquals("unauthorized", body.getMessage());
    }
}
