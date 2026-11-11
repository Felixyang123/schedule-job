package com.wly.job.server.auth;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.AdminUser;
import com.wly.job.server.dao.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "schedule.admin", name = "bootstrap-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AdminUserInitializer {

    private final AdminUserMapper userMapper;
    private final ScheduleProps props;
    private final BCryptPasswordEncoder passwordEncoder;

    @jakarta.annotation.PostConstruct
    public void initialize() {
        String username = props.getAdmin().getUsername();
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("schedule.admin.username must be configured");
        }
        if (userMapper.selectByUsername(username) != null) {
            return;
        }
        String rawPassword = props.getAdmin().getPassword();
        if (!StringUtils.hasText(rawPassword) || rawPassword.length() < 8) {
            throw new IllegalStateException("bootstrap admin password must contain at least 8 characters");
        }
        Date now = new Date();
        userMapper.insert(AdminUser.builder()
                .accountId(UUID.randomUUID().toString().replace("-", ""))
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .enabled(true)
                .mustChangePassword(true)
                .createTime(now)
                .updateTime(now)
                .build());
    }
}
