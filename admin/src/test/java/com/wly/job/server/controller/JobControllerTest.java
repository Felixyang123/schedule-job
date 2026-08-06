package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.service.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobController} 单元测试：验证端点映射、服务委托与统一响应包装。
 */
@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService)).build();
    }

    @Test
    void pageDelegatesToService() throws Exception {
        PageResp<JobResp> resp = PageResp.of(List.of(JobResp.builder().id(1L).name("job-a").build()),
                1, 10, 1);
        when(jobService.page(any())).thenReturn(resp);

        mockMvc.perform(post("/admin/job/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PageReq<>())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.records[0].name").value("job-a"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void detailReturnsJobWhenFound() throws Exception {
        when(jobService.detail(1L)).thenReturn(JobResp.builder().id(1L).name("job-a").build());

        mockMvc.perform(get("/admin/job/detail").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.name").value("job-a"));
    }

    @Test
    void detailReturnsFailWhenNotFound() throws Exception {
        when(jobService.detail(1L)).thenReturn(null);

        mockMvc.perform(get("/admin/job/detail").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    @Test
    void editDelegatesToService() throws Exception {
        EditJobReq req = new EditJobReq();
        req.setId(1L);

        mockMvc.perform(post("/admin/job/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
        verify(jobService).edit(eq(req));
    }

    @Test
    void switchStatusDelegatesToService() throws Exception {
        mockMvc.perform(post("/admin/job/switch").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
        verify(jobService).switchStatus(1L);
    }

    @Test
    void execDelegatesToService() throws Exception {
        ExecJobReq req = new ExecJobReq();
        req.setJobId(1L);

        mockMvc.perform(post("/admin/job/exec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
        verify(jobService).exec(eq(req));
    }

    @Test
    void deleteDelegatesToService() throws Exception {
        mockMvc.perform(post("/admin/job/delete").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
        verify(jobService).delete(1L);
    }
}
