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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RequestLogFilter} 过滤器注册顺序集成测试：验证在真实 Spring 注册下
 * {@code ForwardedHeaderFilter}（Boot 自动注册，顺序 HIGHEST_PRECEDENCE）先于本过滤器
 * （HIGHEST_PRECEDENCE + 1）执行，反代头修正后的 clientIp 进入请求日志。
 *
 * <p>使用 it profile 的轻量上下文（不触发 SQL；/admin/** 因未配置 admin.password 走 Fail-Closed 401，
 * 恰好作为无业务干扰的响应）。断言日志用 logback {@link ListAppender} 捕获。
 */
@ActiveProfiles("it")
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
class RequestLogFilterWebTest {

    private final Logger log = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level originalLevel = log.getLevel();

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void clientIpUsesForwardedForThroughRealFilterChain() throws Exception {
        // /admin/job/page 为 POST；不带会话 token，AdminAuthInterceptor 必然 401（无论 admin.password
        // 是否因 profile 合并而存在，Fail-Closed 语义一致），作为无业务干扰的响应
        mockMvc.perform(post("/admin/job/page").header("X-Forwarded-For", "198.51.100.23"))
                .andExpect(status().isUnauthorized());

        assertFalse(appender.list.isEmpty(), "请求日志必须产生");
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("clientIp=198.51.100.23"),
                "clientIp 必须是 ForwardedHeaderFilter 修正后的真实客户端 IP，log=" + msg);
    }
}
