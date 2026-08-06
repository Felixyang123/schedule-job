package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.resp.GroupResp;
import com.wly.job.server.service.GroupService;
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
 * {@link GroupController} 单元测试：验证端点映射、服务委托与统一响应包装。
 */
@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock
    private GroupService groupService;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(groupService)).build();
    }

    @Test
    void listAllDelegatesToService() throws Exception {
        when(groupService.listAll()).thenReturn(List.of(GroupResp.builder().id(1L).name("g1").build()));

        mockMvc.perform(get("/admin/group/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data[0].name").value("g1"));
    }

    @Test
    void addDelegatesToService() throws Exception {
        AddGroupReq req = new AddGroupReq();
        req.setName("g1");

        mockMvc.perform(post("/admin/group/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
        verify(groupService).add(eq(req));
    }

    @Test
    void pageDelegatesToService() throws Exception {
        PageResp<GroupResp> resp = PageResp.of(List.of(GroupResp.builder().id(1L).name("g1").build()),
                1, 10, 1);
        when(groupService.page(any())).thenReturn(resp);

        mockMvc.perform(post("/admin/group/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PageReq<>())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.records[0].name").value("g1"));
    }
}
