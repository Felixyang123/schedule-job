package com.wly.job.server.convert;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.pojo.resp.JobResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobBeanConverterTest {

    @Test
    void convertMapsFinishedFlag() {
        Job job = Job.builder().id(1L).name("demo").type(0).finished(1).build();
        JobResp resp = JobBeanConverter.convert(job);
        assertNotNull(resp);
        assertEquals(1, resp.getFinished());
    }

    @Test
    void initDefaultsFinishedToZero() {
        Job job = Job.builder().build().init();
        assertEquals(0, job.getFinished());
    }
}
