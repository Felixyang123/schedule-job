package com.wly.job.server.auth;

import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.bean.LoginInfo;
import com.wly.sso.core.config.LoginPropsConfig;
import com.wly.sso.core.dto.SsoErrorCode;
import com.wly.sso.core.exception.SsoException;
import com.wly.sso.core.handler.LoginContext;
import com.wly.sso.core.helper.AuthHelper;
import com.wly.sso.core.utils.TokenUtils;

public class DatabaseAuthHelper implements AuthHelper {

    private final AdminUserMapper userMapper;
    private final LoginPropsConfig loginPropsConfig;

    public DatabaseAuthHelper(AdminUserMapper userMapper, LoginPropsConfig loginPropsConfig) {
        this.userMapper = userMapper;
        this.loginPropsConfig = loginPropsConfig;
    }

    @Override
    public LoginInfo authenticate(LoginContext context) {
        AdminUser user = userMapper.selectByAccountId(context.getAccountId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new SsoException(SsoErrorCode.LOGIN_REQUIRED);
        }
        return LoginInfo.builder()
                .accountId(user.getAccountId())
                .accountName(user.getUsername())
                .signature(TokenUtils.getSignature())
                .expireTime(System.currentTimeMillis() + loginPropsConfig.getTokenTimeout())
                .build();
    }
}
