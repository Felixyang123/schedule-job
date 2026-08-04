package com.wly.job.server.dao.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JobViewTest {

    @Test
    void ofCopiesOnlyProjectionFields() {
        Job job = Job.builder()
                .id(1L).name("j").cron("0/5 * * * * ?").executeParam("p")
                .strategy(2).type(1).description("desc").status(1).finished(0)
                .creator("u").build();

        JobView view = JobView.of(job);

        assertEquals(1L, view.id());
        assertEquals("j", view.name());
        assertEquals("0/5 * * * * ?", view.cron());
        assertEquals("p", view.executeParam());
        assertEquals(2, view.strategy());
        assertEquals(1, view.type());

        Job roundTrip = view.toJob();
        assertEquals(1L, roundTrip.getId());
        assertEquals("j", roundTrip.getName());
        assertEquals("p", roundTrip.getExecuteParam());
        assertNull(roundTrip.getDescription());
        assertNull(roundTrip.getCreator());
    }
}
