package com.wly.job.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScheduleProps} 绑定回归测试：嵌套点号属性（schedule.admin.* / schedule.ha.*）
 * 必须以嵌套类承载（Admin/Ha）。曾因扁平字段无法接收多段点号属性而静默不绑定
 * （adminPassword 恒 null、ha.enabled 恒 false），本测试防回归。
 */
@ActiveProfiles("it")
@SpringBootTest(properties = {
        "schedule.admin.username=bind-user",
        "schedule.admin.password=bind-pass",
        "schedule.ha.enabled=true",
        "schedule.ha.election=REDIS"
})
class SchedulePropsBindingTest {

    @Autowired
    private ScheduleProps props;

    @Test
    void nestedDottedPropertiesBindToNestedClasses() {
        assertEquals("bind-user", props.getAdmin().getUsername());
        assertEquals("bind-pass", props.getAdmin().getPassword());
        assertTrue(props.getHa().isEnabled(), "schedule.ha.enabled 必须绑定到嵌套 Ha.enabled");
        assertEquals("REDIS", props.getHa().getElection());
    }
}
