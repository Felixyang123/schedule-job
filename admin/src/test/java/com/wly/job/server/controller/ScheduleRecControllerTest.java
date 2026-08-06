package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.pojo.req.QueryScheduleRecReq;
import com.wly.job.server.pojo.resp.ScheduleRecResp;
import com.wly.job.server.service.ScheduleRecService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ScheduleRecController} 单元测试：验证端点映射、服务委托与统一响应包装。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleRecControllerTest {

    @Mock
    private ScheduleRecService recService;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduleRecController(recService)).build();
    }

    @Test
    void pageDelegatesToService() throws Exception {
        PageResp<ScheduleRecResp> resp = PageResp.of(
                List.of(ScheduleRecResp.builder().id(1L).requestId("r1").build()), 1, 10, 1);
        when(recService.page(any())).thenReturn(resp);

        mockMvc.perform(post("/admin/schedule/rec/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PageReq<QueryScheduleRecReq>())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.records[0].requestId").value("r1"));
    }

    @Test
    void detailReturnsRecWhenFound() throws Exception {
        when(recService.detail("r1")).thenReturn(ScheduleRecResp.builder().id(1L).requestId("r1").build());

        mockMvc.perform(get("/admin/schedule/rec/detail").param("requestId", "r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.requestId").value("r1"));
    }

    @Test
    void detailReturnsFailWhenNotFound() throws Exception {
        when(recService.detail("r1")).thenReturn(null);

        mockMvc.perform(get("/admin/schedule/rec/detail").param("requestId", "r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("调度记录不存在"));
    }
}
