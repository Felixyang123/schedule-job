package com.wly.job.server.schedule.engine;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.schedule.ScheduleJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayQueueSchedulerEngineTest {

    private static ScheduleJob job(long id) {
        return ScheduleJob.of(JobView.of(Job.builder().id(id).name("j" + id)
                .cron("0/5 * * * * ?").type(0).finished(0).build()));
    }

    @Test
    void clearRemovesAllEntries() {
        DelayQueueSchedulerEngine engine = new DelayQueueSchedulerEngine();
        engine.add(job(1L));
        engine.add(job(2L));

        assertFalse(engine.isEmpty());
        engine.clear();

        assertTrue(engine.isEmpty());
    }
}
