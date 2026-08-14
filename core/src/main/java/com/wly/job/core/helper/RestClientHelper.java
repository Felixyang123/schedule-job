package com.wly.job.core.helper;

import com.wly.job.common.exception.ScheduleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Consumer;

/**
 * REST 客户端工具类，支持泛型返回值与统一异常处理。
 *
 * <p>Worker 侧 HTTP 通信封装（基于 Spring {@link RestClient}）：用于向 Admin 发送
 * 作业注册与实例心跳（{@code /open/job/register}、{@code /open/job/instance/register}）。
 * 所有 RestClientException 统一转换为 {@link ScheduleException} 抛出；
 * 以 Builder 方式配置 baseUrl、默认 Header 与 Bearer Token（accessToken 鉴权）。
 */
public class RestClientHelper {
    private final RestClient restClient;

    private RestClientHelper(Builder builder) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        if (builder.baseUrl != null) {
            restClientBuilder = restClientBuilder.baseUrl(builder.baseUrl);
        }
        if (builder.defaultHeaders != null) {
            restClientBuilder = restClientBuilder.defaultHeaders(builder.defaultHeaders);
        }
        if (builder.connectTimeout > 0 || builder.readTimeout > 0) {
            // 显式配置 connect/read 超时，防止 Admin 半开（TCP 可连但 HTTP 无响应）时心跳线程长期阻塞
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(builder.connectTimeout);
            requestFactory.setReadTimeout(builder.readTimeout);
            restClientBuilder = restClientBuilder.requestFactory(requestFactory);
        }

        this.restClient = restClientBuilder.build();
    }

    /**
     * POST 请求，返回泛型类型
     */
    public <T> T post(String url, Object request, ParameterizedTypeReference<T> responseType) {
        try {
            RestClient.RequestBodySpec requestSpec = buildRequest(url, request);
            return requestSpec.retrieve().body(responseType);
        } catch (RestClientException e) {
            throw new ScheduleException("HTTP request fail: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 POST 请求体（application/json，统一 JSON 编解码）
     */
    private RestClient.RequestBodySpec buildRequest(String url, Object request) {
        RestClient.RequestBodySpec bodySpec = restClient.method(HttpMethod.POST)
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        if (request != null) {
            bodySpec.body(request);
        }

        return bodySpec;
    }

    /**
     * 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private Consumer<HttpHeaders> defaultHeaders;
        /** 连接超时（毫秒），默认 2000ms；0 表示不显式设置 */
        private int connectTimeout = 2000;
        /** 读超时（毫秒），默认 3000ms；0 表示不显式设置 */
        private int readTimeout = 3000;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置连接超时（毫秒）。约束：单次 HTTP 尝试超时 ≤ (Admin 租约剔除时间 − 心跳间隔) / Admin 节点数，
         * 例：剔除 30s、心跳 10s（宽限 20s）、5 节点 → 单次尝试须 ≤ 4s。
         */
        public Builder connectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置读超时（毫秒）。约束：单次 HTTP 尝试超时 ≤ (Admin 租约剔除时间 − 心跳间隔) / Admin 节点数，
         * 例：剔除 30s、心跳 10s（宽限 20s）、5 节点 → 单次尝试须 ≤ 4s。
         */
        public Builder readTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder defaultHeaders(Consumer<HttpHeaders> headersConsumer) {
            this.defaultHeaders = headersConsumer;
            return this;
        }

        public Builder defaultHeader(String name, String value) {
            if (this.defaultHeaders == null) {
                this.defaultHeaders = headers -> headers.add(name, value);
            } else {
                this.defaultHeaders = this.defaultHeaders.andThen(headers -> headers.add(name, value));
            }
            return this;
        }

        public Builder bearerToken(String token) {
            return defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        /**
         * 设置凭证身份 Header（ADR-0006）：{@code X-Job-Group} 为应用身份
         * （applicationName），{@code X-Job-Env} 为环境（env），供 Admin 的
         * {@code /open/**} 拦截器按身份查表校验凭证摘要。
         */
        public Builder credentialIdentity(String applicationName, String env) {
            if (applicationName == null || applicationName.isBlank()) {
                throw new IllegalArgumentException("applicationName must not be blank");
            }
            this.defaultHeader("X-Job-Group", applicationName);
            this.defaultHeader("X-Job-Env", env == null ? "default" : env);
            return this;
        }

        public RestClientHelper build() {
            return new RestClientHelper(this);
        }
    }
}
