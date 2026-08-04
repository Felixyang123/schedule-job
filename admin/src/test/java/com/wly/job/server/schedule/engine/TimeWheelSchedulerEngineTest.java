package com.wly.job.server.schedule.engine;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.schedule.ScheduleJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWheelSchedulerEngineTest {

    @Test
    void clearEmptiesReadyQueue() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ScheduleJob base = ScheduleJob.of(JobView.of(Job.builder().id(1L).name("j")
                .cron("0/5 * * * * ?").type(0).finished(0).build()));
        engine.add(new ScheduleJob(base.job(), System.nanoTime() - 1_000_000L));

        engine.clear();

        assertTrue(engine.isEmpty());
    }
}
