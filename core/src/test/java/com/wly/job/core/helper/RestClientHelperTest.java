package com.wly.job.core.helper;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 {@link RestClientHelper} 构建出的 {@code RestClient} 已配置 HTTP
 * connect/read 超时（Worker 心跳/注册的半开 TCP 保护，Spec §2.3）。
 * <p>
 * 通过反射提取内置 RestClient 的请求工厂与 {@link SimpleClientHttpRequestFactory}
 * 的私有超时字段断言（RestClient 与 SimpleClientHttpRequestFactory 均未暴露 getter）。
 */
class RestClientHelperTest {

    private static final int CONNECT_TIMEOUT = 1234;
    private static final int READ_TIMEOUT = 2345;

    @Test
    void customTimeoutsArePropagatedToRequestFactory() throws Exception {
        RestClientHelper helper = RestClientHelper.builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();

        SimpleClientHttpRequestFactory factory =
                assertInstanceOf(SimpleClientHttpRequestFactory.class, requestFactoryOf(helper));

        assertEquals(CONNECT_TIMEOUT, intFieldOf(factory, "connectTimeout"));
        assertEquals(READ_TIMEOUT, intFieldOf(factory, "readTimeout"));
    }

    @Test
    void defaultTimeoutsAreAppliedWhenNotSpecified() throws Exception {
        RestClientHelper helper = RestClientHelper.builder().build();

        SimpleClientHttpRequestFactory factory =
                assertInstanceOf(SimpleClientHttpRequestFactory.class, requestFactoryOf(helper));

        assertEquals(2000, intFieldOf(factory, "connectTimeout"));
        assertEquals(3000, intFieldOf(factory, "readTimeout"));
    }

    @Test
    void zeroTimeoutsLeaveRestClientDefaultRequestFactory() throws Exception {
        RestClientHelper helper = RestClientHelper.builder()
                .connectTimeout(0)
                .readTimeout(0)
                .build();

        // 0 表示不显式配置超时：不设置 requestFactory，回落到 RestClient 内置默认工厂（JdkClientHttpRequestFactory）
        ClientHttpRequestFactory factory = requestFactoryOf(helper);

        assertFalse(factory instanceof SimpleClientHttpRequestFactory);
    }

    /** 提取 RestClientHelper 内置 RestClient 的请求工厂（沿类层级查找 ClientHttpRequestFactory 字段）。 */
    private static ClientHttpRequestFactory requestFactoryOf(RestClientHelper helper) throws Exception {
        Field restClientField = RestClientHelper.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);
        Object restClient = restClientField.get(helper);
        assertNotNull(restClient, "restClient 未构建");

        for (Class<?> clazz = restClient.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (ClientHttpRequestFactory.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (ClientHttpRequestFactory) field.get(restClient);
                }
            }
        }
        throw new AssertionError("未在 RestClient 实现中找到 ClientHttpRequestFactory 字段");
    }

    private static int intFieldOf(SimpleClientHttpRequestFactory factory, String name) throws Exception {
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(factory);
    }
}
