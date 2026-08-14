package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.server.credential.CredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link OpenApiTokenInterceptor}，仅对 {@code /open/**} 生效。
 *
 * <p>鉴权范围限定开放接口（作业注册、实例心跳），管控接口 {@code /admin/**} 本轮不拦截。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CredentialService credentialService;
    private final ObjectMapper objectMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OpenApiTokenInterceptor(credentialService, objectMapper))
                .addPathPatterns("/open/**");
    }
}
