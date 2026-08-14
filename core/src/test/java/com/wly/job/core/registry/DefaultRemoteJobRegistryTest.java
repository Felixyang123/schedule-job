package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.selector.HashAdminNodeSelector;
import com.wly.job.core.selector.RoundRobinAdminNodeSelector;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultRemoteJobRegistryTest {

    private final RestClientHelper first = mock(RestClientHelper.class);
    private final RestClientHelper second = mock(RestClientHelper.class);

    private JobInstance instance() {
        return JobInstance.builder().discoveryKey("group-a").host("10.0.0.1").port(8101).build();
    }

    private JobInfo jobInfo() {
        return JobInfo.builder().jobname("job-a").group("group-a").cron("0/5 * * * * ?").build();
    }

    @Test
    void failoverToNextHelperOnNetworkError() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        when(second.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));

        verify(second).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void stopsAfterFirstSuccess() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        registry.register(instance());

        verify(first).post(anyString(), any(), any(ParameterizedTypeReference.class));
        verify(second, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void allFailuresAreSwallowed() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        when(second.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));
    }

    @Test
    void businessFailureDoesNotFailover() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.fail("rejected"));
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        registry.register(instance());

        verify(second, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void emptyAddressListIsNoOp() {
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));
    }

    @Test
    void jobRegisterPostsJobInfoToJobRegisterPath() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first), new RoundRobinAdminNodeSelector());
        JobInfo jobInfo = jobInfo();

        registry.register(jobInfo, instance().getInstanceKey());

        // instanceKey 只参与节点选择，请求体仍是纯 JobInfo
        verify(first).post(eq("/open/job/register"), eq(jobInfo), any(ParameterizedTypeReference.class));
    }

    @Test
    void jobRegisterAndInstanceRegisterShareSameHashTarget() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        when(second.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        // HASH 选择器按 key 稳定钉住节点：同一 instanceKey 的两类注册必须命中同一 Admin
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new HashAdminNodeSelector());
        JobInstance instance = instance();

        registry.register(jobInfo(), instance.getInstanceKey());
        registry.register(instance);

        RestClientHelper picked = mockingDetails(first).getInvocations().isEmpty() ? second : first;
        RestClientHelper other = picked == first ? second : first;
        verify(picked, times(2)).post(anyString(), any(), any(ParameterizedTypeReference.class));
        verify(other, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void nullJobInfoIsNoOp() {
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register((JobInfo) null, "group-a:10.0.0.1:8101"));

        verify(first, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }
}
