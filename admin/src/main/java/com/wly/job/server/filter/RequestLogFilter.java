package com.wly.job.server.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 统一 HTTP 请求日志 + {@code X-Request-Id} 透传过滤器（Spec 2026-08-06 §2.3/§2.4）。
 *
 * <p>职责：为 {@code /admin/**} 与 {@code /open/**} 请求生成/透传 {@code requestId}，
 * 写入 MDC（key={@code requestId}）、回传响应头 {@code X-Request-Id}，并在请求结束后记一条
 * 日志：{@code method url status 耗时 requestId clientIp}。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code X-Request-Id} 头缺失时生成 UUID；外部注入的头部按 64 字符截断（防恶意超长头，Spec §6 风险项）；</li>
 *   <li>{@code /actuator} 与 {@code /actuator/**}（健康检查高频噪音）不记日志、不写 MDC，直接放行；</li>
 *   <li>每个请求恰好一条日志：成功 {@code info}；异常逃逸记 {@code error}（含异常栈，栈上携带 traceId，
 *       避免容器在 filter 之外打栈时 MDC 已清理导致链路断档），随后原样抛出；</li>
 *   <li>不记录请求/响应 body（敏感数据 + 日志膨胀，Spec §2.4）；</li>
 *   <li>{@code finally} 中清 MDC，避免泄漏到其他请求线程。</li>
 * </ul>
 *
 * <p>顺序约束：必须运行在 Spring Boot 自动注册的 {@link org.springframework.web.filter.ForwardedHeaderFilter}
 * 之后——该过滤器在 {@code server.forward-headers-strategy: framework} 下以
 * {@code Ordered.HIGHEST_PRECEDENCE} 注册（部署拓扑为 NG → 业务网关 → admin，边缘覆盖写
 * {@code X-Forwarded-*}，admin 仅网关可达），本过滤器取 {@code getRemoteAddr()} 作 clientIp 时
 * 必须已是修正后的真实客户端地址，故本过滤器取 {@code HIGHEST_PRECEDENCE + 1}。
 *
 * <p>本过滤器为 Servlet {@link Filter}，运行在 Spring MVC 拦截器外层，与
 * {@code OpenApiTokenInterceptor}（鉴权）相互独立、互不影响。
 *
 * <p>注册方式：{@code @Component} + {@code @Order}——Spring Boot 自动注册到 {@code /*}，
 * 且优先于业务过滤器执行，保证下游链路拿到统一的 {@code requestId}。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLogFilter implements Filter {

    /** 透传/回传的请求追踪头。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key：HTTP 层链路追踪 ID（traceId），贯穿整个请求/调度链路不变。 */
    public static final String MDC_KEY = "traceId";

    /** 外部注入的 requestId 最大长度，超长截断。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    /** 排除路径：健康检查高频请求不记日志（裸 /actuator 也会被 Spring 重定向，同样排除）。 */
    private static final String ACTUATOR_PATH = "/actuator";

    private static final String ACTUATOR_PREFIX = "/actuator/";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        String uri = request.getRequestURI();
        if (isActuatorPath(uri)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        String requestId = normalizeRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(servletRequest, servletResponse);
            log.info("method={} url={} status={} cost={}ms traceId={} clientIp={}",
                    request.getMethod(), uri, response.getStatus(),
                    System.currentTimeMillis() - start, requestId, request.getRemoteAddr());
        } catch (Exception e) {
            // 异常逃逸时的 status：以客户端实际将收到的为准——响应已提交则记已提交状态（客户端确实看到了它），
            // 未提交则记 500（容器错误处理将以此状态应答）；异常栈挂在 error 日志上，保证栈带 traceId。
            int status = response.isCommitted()
                    ? response.getStatus()
                    : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            log.error("request failed, method={} url={} status={} cost={}ms traceId={} clientIp={}",
                    request.getMethod(), uri, status, System.currentTimeMillis() - start,
                    requestId, request.getRemoteAddr(), e);
            throw e;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 健康检查路径判定：裸 {@code /actuator} 与其子路径均排除。 */
    private static boolean isActuatorPath(String uri) {
        return ACTUATOR_PATH.equals(uri) || uri.startsWith(ACTUATOR_PREFIX);
    }

    /**
     * 规范化外部传入的 {@code X-Request-Id}：空白头视为缺失（生成 UUID），超长截断至 64 字符。
     */
    private static String normalizeRequestId(String header) {
        if (header == null || header.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = header.trim();
        return trimmed.length() > MAX_REQUEST_ID_LENGTH
                ? trimmed.substring(0, MAX_REQUEST_ID_LENGTH)
                : trimmed;
    }
}
