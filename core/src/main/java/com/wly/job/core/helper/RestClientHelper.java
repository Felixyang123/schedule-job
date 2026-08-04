package com.wly.job.core.helper;

import com.wly.job.common.exception.ScheduleException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.function.Consumer;

/**
 * REST 客户端工具类
 * 支持泛型参数和返回值，统一的异常处理
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

        this.restClient = restClientBuilder.build();
    }

    /**
     * GET 请求
     */
    public <T> T get(String url, Class<T> responseType) {
        return executeRequest(url, HttpMethod.GET, null, responseType, null);
    }

    public <T> T get(String url, ParameterizedTypeReference<T> responseType) {
        return executeRequest(url, HttpMethod.GET, null, responseType, null);
    }

    public <T> T get(String url, Class<T> responseType, Map<String, Object> uriVariables) {
        return executeRequest(url, HttpMethod.GET, null, responseType, uriVariables);
    }

    public <T> T get(String url, ParameterizedTypeReference<T> responseType, Map<String, Object> uriVariables) {
        return executeRequest(url, HttpMethod.GET, null, responseType, uriVariables);
    }

    /**
     * POST 请求
     */
    public <T> T post(String url, Object request, Class<T> responseType) {
        return executeRequest(url, HttpMethod.POST, request, responseType, null);
    }

    public <T> T post(String url, Object request, ParameterizedTypeReference<T> responseType) {
        return executeRequest(url, HttpMethod.POST, request, responseType, null);
    }

    public <T> T post(String url, Object request, Class<T> responseType, Map<String, Object> uriVariables) {
        return executeRequest(url, HttpMethod.POST, request, responseType, uriVariables);
    }

    public <T> T post(String url, Object request, ParameterizedTypeReference<T> responseType, Map<String, Object> uriVariables) {
        return executeRequest(url, HttpMethod.POST, request, responseType, uriVariables);
    }

    /**
     * PUT 请求
     */
    public <T> T put(String url, Object request, Class<T> responseType) {
        return executeRequest(url, HttpMethod.PUT, request, responseType, null);
    }

    public <T> T put(String url, Object request, ParameterizedTypeReference<T> responseType) {
        return executeRequest(url, HttpMethod.PUT, request, responseType, null);
    }

    /**
     * DELETE 请求
     */
    public <T> T delete(String url, Class<T> responseType) {
        return executeRequest(url, HttpMethod.DELETE, null, responseType, null);
    }

    public <T> T delete(String url, ParameterizedTypeReference<T> responseType) {
        return executeRequest(url, HttpMethod.DELETE, null, responseType, null);
    }

    /**
     * 执行请求的核心方法
     */
    private <T> T executeRequest(String url, HttpMethod method, Object request,
                                 Class<T> responseType, Map<String, Object> uriVariables) {
        try {
            RestClient.RequestBodySpec requestSpec = buildRequest(url, method, request, uriVariables);

            if (responseType == Void.class) {
                requestSpec.retrieve().toBodilessEntity();
                return null;
            } else {
                return requestSpec.retrieve().body(responseType);
            }
        } catch (RestClientException e) {
            throw new ScheduleException("HTTP request fail: " + e.getMessage(), e);
        }
    }

    private <T> T executeRequest(String url, HttpMethod method, Object request,
                                 ParameterizedTypeReference<T> responseType, Map<String, Object> uriVariables) {
        try {
            RestClient.RequestBodySpec requestSpec = buildRequest(url, method, request, uriVariables);
            return requestSpec.retrieve().body(responseType);
        } catch (RestClientException e) {
            throw new ScheduleException("HTTP request fail: " + e.getMessage(), e);
        }
    }

    /**
     * 构建请求
     */
    private RestClient.RequestBodySpec buildRequest(String url, HttpMethod method,
                                                    Object request, Map<String, Object> uriVariables) {
        RestClient.RequestBodyUriSpec requestSpec = restClient.method(method);

        if (uriVariables != null) {
            requestSpec.uri(url, uriVariables);
        } else {
            requestSpec.uri(url);
        }

        RestClient.RequestBodySpec bodySpec = requestSpec
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        if (request != null) {
            bodySpec.body(request);
        }

        return bodySpec;
    }

    /**
     * 自定义请求（高级用法）
     */
    public <T> T exchange(String url, HttpMethod method, Object request,
                          HttpHeaders headers, ParameterizedTypeReference<T> responseType) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.method(method)
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (headers != null) {
                requestSpec.headers(httpHeaders -> httpHeaders.addAll(headers));
            }

            if (request != null) {
                requestSpec.body(request);
            }

            return requestSpec.retrieve().body(responseType);
        } catch (RestClientException e) {
            throw new ScheduleException("HTTP request fail:" + e.getMessage(), e);
        }
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

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public RestClientHelper build() {
            return new RestClientHelper(this);
        }
    }
}