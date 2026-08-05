package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.bean.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenApiTokenInterceptor} 单元测试：直接实例化拦截器，用 Mock 的
 * HttpServletRequest / HttpServletResponse 驱动，无需 Spring 上下文。
 *
 * <p>覆盖验收标准 1：未配置 token → 401；正确 Bearer → 放行；错误 token → 401；
 * 裸 token → 放行；失败响应体为 {@code Result.fail("unauthorized")}。
 */
class OpenApiTokenInterceptorTest {

    private static final String TOKEN = "secret-token";

    private ScheduleProps props;
    private OpenApiTokenInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        props = new ScheduleProps();
        interceptor = new OpenApiTokenInterceptor(props, new ObjectMapper());
        request = new MockHttpServletRequest();
        request.setRequestURI("/open/job/register");
        request.setRemoteAddr("192.168.1.10");
        response = new MockHttpServletResponse();
    }

    // ---------- 未配置 token：默认拒绝 ----------

    @Test
    void rejectsWhenTokenNotConfigured() throws Exception {
        props.setAccessToken(null);
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenTokenConfiguredBlank() throws Exception {
        props.setAccessToken("   ");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    // ---------- 正确 token：放行 ----------

    @Test
    void passesWithCorrectBearerToken() throws Exception {
        props.setAccessToken(TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void passesWithCorrectBareToken() throws Exception {
        props.setAccessToken(TOKEN);
        request.addHeader("Authorization", TOKEN);

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());
    }

    @Test
    void passesWithCaseInsensitiveBearerPrefix() throws Exception {
        props.setAccessToken(TOKEN);
        request.addHeader("Authorization", "bEaReR " + TOKEN);

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());
    }

    // ---------- 错误 token：拒绝 ----------

    @Test
    void rejectsWithWrongBearerToken() throws Exception {
        props.setAccessToken(TOKEN);
        request.addHeader("Authorization", "Bearer wrong-token");

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWithWrongBareToken() throws Exception {
        props.setAccessToken(TOKEN);
        request.addHeader("Authorization", "wrong-token");

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenAuthorizationHeaderMissing() throws Exception {
        props.setAccessToken(TOKEN);

        assertFalse(interceptor.preHandle(request, response, null));
        assertUnauthorizedResponse();
    }

    @Test
    void rejectsWhenBearerPrefixOnly() throws Exception {
        props.setAccessToken(TOKEN);
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
        assertEquals(Result.FAIL_CODE, body.getCode());
        assertEquals("unauthorized", body.getMessage());
    }
}
