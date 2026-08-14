package com.wly.job.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.server.credential.CredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：
 * <ul>
 *   <li>{@link OpenApiTokenInterceptor}：仅 {@code /open/**}（作业注册、实例心跳的凭证鉴权）；</li>
 *   <li>{@link AdminAuthInterceptor}：全部 {@code /admin/**} 管控接口（登录会话鉴权），
 *       豁免登录 / 登出接口本身。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CredentialService credentialService;
    private final AdminSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final ScheduleProps props;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OpenApiTokenInterceptor(credentialService, objectMapper))
                .addPathPatterns("/open/**");
        registry.addInterceptor(new AdminAuthInterceptor(sessionRegistry, objectMapper, props))
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/auth/login", "/admin/auth/logout");
    }
}
