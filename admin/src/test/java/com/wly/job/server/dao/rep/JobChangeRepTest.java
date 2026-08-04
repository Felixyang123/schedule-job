package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.mapper.JobChangeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobChangeRepTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), JobChange.class);
    }

    private final JobChangeMapper mapper = mock(JobChangeMapper.class);
    private final JobChangeRep rep = new JobChangeRep(mapper);

    private static JobChange change(long id) {
        JobChange c = new JobChange();
        c.setId(id);
        c.setJobId(7L);
        return c;
    }

    @Test
    void recordInsertsAllFields() {
        rep.record(7L, 6, "system", "req-1", "job-a");

        ArgumentCaptor<JobChange> captor = ArgumentCaptor.forClass(JobChange.class);
        verify(mapper).insert(captor.capture());
        JobChange change = captor.getValue();
        assertEquals(7L, change.getJobId());
        assertEquals(6, change.getChangeType());
        assertEquals("system", change.getOperator());
        assertEquals("req-1", change.getRequestId());
        assertEquals("job-a", change.getJobName());
    }

    @Test
    void listAfterQueriesAboveWatermarkOrderedAsc() {
        when(mapper.selectList(any())).thenReturn(List.of(change(11L), change(12L)));

        List<JobChange> result = rep.listAfter(10L, 500);

        assertEquals(2, result.size());
        verify(mapper).selectList(any());
    }

    @Test
    void maxIdReturnsLastIdOrZero() {
        when(mapper.selectOne(any())).thenReturn(change(42L));
        assertEquals(42L, rep.maxId());

        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(0L, rep.maxId());
    }

    @Test
    void deleteUpToRemovesConsumedRows() {
        when(mapper.delete(any())).thenReturn(3);
        assertEquals(3, rep.deleteUpTo(500L));
    }
}
