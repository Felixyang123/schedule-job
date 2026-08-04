package com.wly.job.server.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件拦截器。
 *
 * <p>分页拦截器使得 Rep / Mapper 层的 {@code page()} 查询自动拼接分页 SQL；
 * Admin 各分页接口（作业 / 分组 / 调度记录）均依赖此配置。
 */
@Configuration
public class MybatisConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
