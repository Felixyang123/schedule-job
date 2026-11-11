package com.wly.job.server.config;

import com.wly.job.server.auth.DatabaseAuthHelper;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.config.LoginPropsConfig;
import com.wly.sso.core.handler.HeaderLoginHandler;
import com.wly.sso.core.handler.LoginHandler;
import com.wly.sso.core.helper.AuthHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class LocalSsoConfiguration {

    @Bean
    public BCryptPasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean(name = "defaultAuthHelper")
    public AuthHelper defaultAuthHelper(AdminUserMapper userMapper, LoginPropsConfig loginPropsConfig) {
        return new DatabaseAuthHelper(userMapper, loginPropsConfig);
    }

    @Bean(name = "loginHandler")
    @Primary
    public LoginHandler loginHandler(LoginPropsConfig loginPropsConfig,
                                     @Qualifier("defaultLoginHandler") LoginHandler defaultLoginHandler) {
        return new HeaderLoginHandler(loginPropsConfig, defaultLoginHandler);
    }
}
