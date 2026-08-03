package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.selector.RoundRobinAdminNodeSelector;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DefaultRemoteJobRegistryTest {

    private final RestClientHelper first = mock(RestClientHelper.class);
    private final RestClientHelper second = mock(RestClientHelper.class);

    private JobInstance instance() {
        return JobInstance.builder().discoveryKey("group-a").host("10.0.0.1").port(8101).build();
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
}
