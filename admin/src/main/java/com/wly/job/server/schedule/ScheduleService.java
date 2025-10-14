package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;

public interface ScheduleService {
    void schedule(String requestId, Job job);
}
