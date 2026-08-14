package com.wly.job.starter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScheduleJobAutoConfiguration#resolveEnv} 环境提取逻辑测试（ADR-0006 决策 #3）。
 */
class ScheduleJobAutoConfigurationTest {

    @Test
    void picksFirstNonTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "dev");
        assertEquals("dev", ScheduleJobAutoConfiguration.resolveEnv(env));
    }

    @Test
    void skipsAllTestProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "it");
        assertEquals("it", ScheduleJobAutoConfiguration.resolveEnv(env));
    }

    @Test
    void defaultsWhenNoProfile() {
        MockEnvironment env = new MockEnvironment();
        assertEquals("default", ScheduleJobAutoConfiguration.resolveEnv(env));
    }

    @Test
    void defaultsWhenOnlyTestProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        assertEquals("default", ScheduleJobAutoConfiguration.resolveEnv(env));
    }
}
