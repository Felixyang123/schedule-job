package com.wly.job.starter.config;

import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.starter.processor.ScheduleJobAnnotationProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 调度框架 Spring Boot 自动配置：将 {@code schedule-job.*} 配置项装配为
 * {@link ScheduleJobCoreFactory}（Worker 核心工厂），并注册
 * {@link ScheduleJobAnnotationProcessor} 负责扫描 {@code @ScheduleJob} 方法与生命周期管理。
 * <p>
 * 通过 {@link EnableScheduleJob} 注解导入，核心工厂 Bean 支持被应用自定义 Bean 覆盖
 *（@ConditionalOnMissingBean）。
 *
 * <p>凭证身份（ADR-0006 决策 #3/#4）：{@code env} 由本配置类从 Spring
 * {@code activeProfiles} 提取（首个非 {@code test} 值，无 profile 时为 {@code default}），
 * 与 {@code schedule-job.application-name} 构成凭证身份维度；{@code application-name}
 * 强制配置，为空启动失败。
 */
@Configuration
@EnableConfigurationProperties(ScheduleJobConfigProps.class)
public class ScheduleJobAutoConfiguration {

    /** 环境提取时跳过的 profile 名（测试环境不算业务环境） */
    private static final String SKIPPED_PROFILE = "test";

    /**
     * 从 activeProfiles 提取环境：首个非 test 值，无匹配时返回 default。
     */
    static String resolveEnv(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (!SKIPPED_PROFILE.equalsIgnoreCase(profile)) {
                return profile;
            }
        }
        return "default";
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleJobCoreFactory scheduleJobCoreFactory(ScheduleJobConfigProps props, Environment environment) {
        String applicationName = props.getApplicationName();
        if (!StringUtils.hasText(applicationName)) {
            throw new IllegalStateException(
                    "schedule-job.application-name must not be blank (credential identity, ADR-0006)");
        }
        String accessToken = props.getAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException(
                    "schedule-job.accessToken must not be blank (plain credential, ADR-0006)");
        }
        return new ScheduleJobCoreFactory(
                props.getPort(),
                props.getServerAddress(),
                accessToken,
                applicationName,
                resolveEnv(environment),
                props.getCredentialVersion(),
                (int) props.getHttpConnectTimeout(),
                (int) props.getHttpReadTimeout(),
                props.getServerSelector(),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getEnabled).orElse(null),
                props.getHeartbeatInterval()
        );
    }

    @Bean
    public ScheduleJobAnnotationProcessor scheduleJobAnnotationProcessor(ScheduleJobCoreFactory factory) {
        return new ScheduleJobAnnotationProcessor(factory);
    }
}
