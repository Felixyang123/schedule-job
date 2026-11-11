package com.wly.job.server.auth;

import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminSessionMapper;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import com.wly.sso.core.dto.SsoErrorCode;
import com.wly.sso.core.exception.SsoException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminUserMapper userMapper;
    private final AdminSessionMapper sessionMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminUser authenticate(String username, String rawPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            throw new SsoException(SsoErrorCode.LOGIN_REQUIRED);
        }
        AdminUser user = userMapper.selectByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new SsoException(SsoErrorCode.LOGIN_REQUIRED);
        }
        return user;
    }

    @Transactional
    public void changePassword(String accountId, String oldPassword, String newPassword) {
        if (!StringUtils.hasText(accountId) || !StringUtils.hasText(oldPassword)
                || !StringUtils.hasText(newPassword) || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new SsoException(SsoErrorCode.PARAM_INVALID, "invalid password change request");
        }
        AdminUser user = userMapper.selectByAccountId(accountId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new SsoException(SsoErrorCode.LOGIN_REQUIRED);
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new SsoException(SsoErrorCode.PARAM_INVALID, "new password must differ from old password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(new Date());
        user.setUpdateTime(new Date());
        userMapper.updateById(user);
        sessionMapper.deleteByAccountId(accountId);
    }
}
