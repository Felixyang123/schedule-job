package com.wly.job.server.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestLogFilter} 单元测试：直接实例化过滤器，用 MockHttpServletRequest/Response
 * 驱动，无需 Spring 上下文；日志断言通过 logback {@link ListAppender} 挂到过滤器 Logger 上捕获。
 *
 * <p>覆盖：/open 请求记日志（含 method/url/status/cost/requestId/clientIp）；/actuator 无日志；
 * 外部头回传；缺头生成 UUID；超长头截断 64 字符；MDC 在请求期间可见、结束后清理。
 */
class RequestLogFilterTest {

    private static final String CLIENT_IP = "192.168.1.10";

    private final RequestLogFilter filter = new RequestLogFilter();
    private final Logger log = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level originalLevel = log.getLevel();

    /** 记录链内（filter 执行期间）MDC 中的 requestId，用于断言 MDC 已注入。 */
    private final AtomicReference<String> mdcDuringChain = new AtomicReference<>();

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        appender.start();
        log.addAppender(appender);
        log.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        log.detachAppender(appender);
        appender.stop();
        log.setLevel(originalLevel);
        MDC.clear();
    }

    // ---------- /open 请求：记日志 ----------

    @Test
    void logsRequestWithAllFieldsForOpenApiPath() throws Exception {
        invoke("POST", "/open/job/register", "abc", 201);

        assertEquals("abc", mdcDuringChain.get());
        assertEquals("abc", response.getHeader(RequestLogFilter.REQUEST_ID_HEADER));

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size());
        ILoggingEvent event = events.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("method=POST"), "log=" + msg);
        assertTrue(msg.contains("url=/open/job/register"), "log=" + msg);
        assertTrue(msg.contains("status=201"), "log=" + msg);
        assertTrue(msg.matches(".*cost=\\d+ms .*"), "log=" + msg);
        assertTrue(msg.contains("traceId=abc"), "log=" + msg);
        assertTrue(msg.contains("clientIp=" + CLIENT_IP), "log=" + msg);

        assertNull(MDC.get(RequestLogFilter.MDC_KEY), "请求结束后 MDC 必须清理");
    }

    // ---------- /actuator：无日志、无 MDC、不回头 ----------

    @Test
    void doesNotLogOrSetMdcForActuatorPath() throws Exception {
        invoke("GET", "/actuator/health", "abc", 200);

        assertNull(mdcDuringChain.get(), "/actuator 不写 MDC");
        assertNull(response.getHeader(RequestLogFilter.REQUEST_ID_HEADER));
        assertTrue(appender.list.isEmpty(), "/actuator 不记请求日志");

        assertNull(MDC.get(RequestLogFilter.MDC_KEY));
    }

    // ---------- 外部 X-Request-Id 透传 ----------

    @Test
    void echoesProvidedRequestIdHeader() throws Exception {
        invoke("GET", "/admin/job/page", "abc", 200);

        assertEquals("abc", mdcDuringChain.get());
        assertEquals("abc", response.getHeader(RequestLogFilter.REQUEST_ID_HEADER));
        assertTrue(appender.list.get(0).getFormattedMessage().contains("traceId=abc"));
    }

    // ---------- 缺头：生成 UUID ----------

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        invoke("GET", "/admin/job/page", null, 200);

        String echoed = response.getHeader(RequestLogFilter.REQUEST_ID_HEADER);
        assertNotNull(echoed);
        assertTrue(echoed.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "应生成 UUID，实际=" + echoed);
        assertEquals(echoed, mdcDuringChain.get());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("traceId=" + echoed));
    }

    // ---------- 超长头截断 ----------

    @Test
    void truncatesOversizedRequestIdHeader() throws Exception {
        String oversized = "x".repeat(100);
        invoke("GET", "/admin/job/page", oversized, 200);

        String echoed = response.getHeader(RequestLogFilter.REQUEST_ID_HEADER);
        assertNotNull(echoed);
        assertEquals(64, echoed.length());
        assertEquals(oversized.substring(0, 64), echoed);
        assertEquals(echoed, mdcDuringChain.get());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("traceId=" + echoed));
        assertFalse(appender.list.get(0).getFormattedMessage().contains(oversized),
                "日志中不得出现超长原始头");
    }

    /**
     * 发起一次过滤调用：链内记录 MDC 的 requestId 并设置响应状态。
     */
    private void invoke(String method, String uri, String requestIdHeader, int statusToSet) throws Exception {
        request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setRemoteAddr(CLIENT_IP);
        if (requestIdHeader != null) {
            request.addHeader(RequestLogFilter.REQUEST_ID_HEADER, requestIdHeader);
        }
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            mdcDuringChain.set(MDC.get(RequestLogFilter.MDC_KEY));
            response.setStatus(statusToSet);
        });
    }
}
