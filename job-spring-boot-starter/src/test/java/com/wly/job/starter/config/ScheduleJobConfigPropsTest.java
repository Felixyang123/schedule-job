package com.wly.job.starter.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleJobConfigPropsTest {

    @Test
    void defaultsAreRoundRobinAndEmptyAddresses() {
        ScheduleJobConfigProps props = new ScheduleJobConfigProps();
        assertEquals("ROUND_ROBIN", props.getServerSelector());
        assertNotNull(props.getServerAddress());
        assertTrue(props.getServerAddress().isEmpty());
    }

    @Test
    void addressesSettable() {
        ScheduleJobConfigProps props = new ScheduleJobConfigProps();
        props.setServerAddress(List.of("http://a:8100", "http://b:8100"));
        assertEquals(2, props.getServerAddress().size());
    }
}
