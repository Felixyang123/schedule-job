package com.wly.job.starter.processor;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.InnerJobRegistry;
import com.wly.job.core.registry.RemoteJobRegistry;
import com.wly.job.starter.annotation.ScheduleJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleJobAnnotationProcessorTest {

    private ScheduleJobAnnotationProcessor processor;

    @AfterEach
    void stopProcessor() {
        if (processor != null && processor.isRunning()) {
            processor.stop();
        }
    }

    @Test
    void registersInstanceBeforeJob() throws Exception {
        var remoteRegistry = mock(RemoteJobRegistry.class);
        var innerRegistry = mock(InnerJobRegistry.class);
        when(innerRegistry.register(any(InnerJob.class))).thenReturn(true);
        processor = processor(remoteRegistry, innerRegistry, false);
        processor.postProcessAfterInitialization(new SingleJobBean(), "singleJobBean");

        processor.start();

        await(() -> invocationCount(remoteRegistry, JobInfo.class) == 1, Duration.ofSeconds(2));
        InOrder order = inOrder(remoteRegistry, innerRegistry);
        order.verify(remoteRegistry).register(any(JobInstance.class));
        order.verify(innerRegistry).register(any(InnerJob.class));
        order.verify(remoteRegistry).register(any(JobInfo.class), anyString());
    }

    @Test
    void groupModeRegistersOneInstanceAndEveryJob() throws Exception {
        var remoteRegistry = mock(RemoteJobRegistry.class);
        var innerRegistry = mock(InnerJobRegistry.class);
        when(innerRegistry.register(any(InnerJob.class))).thenReturn(true);
        processor = processor(remoteRegistry, innerRegistry, true);
        processor.postProcessAfterInitialization(new GroupJobBean(), "groupJobBean");

        processor.start();

        await(() -> invocationCount(remoteRegistry, JobInfo.class) == 2, Duration.ofSeconds(2));
        verify(remoteRegistry).register(any(JobInstance.class));
        verify(remoteRegistry, org.mockito.Mockito.times(2)).register(any(JobInfo.class), anyString());
        assertEquals(1, processor.pendingHeartbeatTaskCount());
    }

    @Test
    void heartbeatFailureIsRetriedWithoutStoppingProcessor() throws Exception {
        var remoteRegistry = mock(RemoteJobRegistry.class);
        var innerRegistry = mock(InnerJobRegistry.class);
        when(innerRegistry.register(any(InnerJob.class))).thenReturn(true);
        var instanceRegistrations = new AtomicInteger();
        doAnswer(invocation -> {
            if (instanceRegistrations.incrementAndGet() == 2) {
                throw new RuntimeException("first heartbeat failed");
            }
            return null;
        }).when(remoteRegistry).register(any(JobInstance.class));
        processor = processor(remoteRegistry, innerRegistry, false, 1L);
        processor.postProcessAfterInitialization(new SingleJobBean(), "singleJobBean");

        processor.start();

        await(() -> instanceRegistrations.get() >= 3, Duration.ofSeconds(4));
        assertTrue(processor.isRunning());
        assertTrue(instanceRegistrations.get() >= 3);
    }

    @Test
    void cannotRestartAfterStopBecauseCoreFactoryIsShutdown() {
        var remoteRegistry = mock(RemoteJobRegistry.class);
        var innerRegistry = mock(InnerJobRegistry.class);
        processor = processor(remoteRegistry, innerRegistry, false);

        processor.start();
        processor.stop();

        assertThrows(IllegalStateException.class, processor::start);
    }

    @Test
    void instanceRegistrationFailureDoesNotBlockJobsOrHeartbeatScheduling() throws Exception {
        var remoteRegistry = mock(RemoteJobRegistry.class);
        var innerRegistry = mock(InnerJobRegistry.class);
        when(innerRegistry.register(any(InnerJob.class))).thenReturn(true);
        doThrow(new RuntimeException("registry unavailable"))
                .when(remoteRegistry).register(any(JobInstance.class));
        processor = processor(remoteRegistry, innerRegistry, true);
        processor.postProcessAfterInitialization(new GroupJobBean(), "groupJobBean");

        processor.start();

        await(() -> invocationCount(remoteRegistry, JobInfo.class) == 2
                && processor.pendingHeartbeatTaskCount() == 1, Duration.ofSeconds(2));
        verify(remoteRegistry).register(any(JobInstance.class));
        verify(remoteRegistry, org.mockito.Mockito.times(2)).register(any(JobInfo.class), anyString());
        assertEquals(1, processor.pendingHeartbeatTaskCount());
    }

    private ScheduleJobAnnotationProcessor processor(RemoteJobRegistry remoteRegistry,
                                                       InnerJobRegistry innerRegistry,
                                                       boolean enableGroup) {
        return processor(remoteRegistry, innerRegistry, enableGroup, 3600L);
    }

    private ScheduleJobAnnotationProcessor processor(RemoteJobRegistry remoteRegistry,
                                                       InnerJobRegistry innerRegistry,
                                                       boolean enableGroup,
                                                       long heartbeatInterval) {
        var factory = mock(ScheduleJobCoreFactory.class);
        when(factory.getRemoteJobRegistry()).thenReturn(remoteRegistry);
        when(factory.getInnerJobRegistry()).thenReturn(innerRegistry);
        when(factory.getInvocationHooks()).thenReturn(List.of());
        when(factory.getPort()).thenReturn(18101);
        when(factory.getHeartbeatInterval()).thenReturn(heartbeatInterval);
        when(factory.getEnableGroup()).thenReturn(enableGroup);
        when(factory.getGroupName()).thenReturn("test-group");
        return new ScheduleJobAnnotationProcessor(factory);
    }

    private int invocationCount(RemoteJobRegistry registry, Class<?> argumentType) {
        return (int) org.mockito.Mockito.mockingDetails(registry).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("register"))
                .filter(invocation -> invocation.getArguments().length > 0)
                .filter(invocation -> argumentType.isInstance(invocation.getArgument(0)))
                .count();
    }

    private void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeout);
    }

    static class SingleJobBean {
        @ScheduleJob(name = "single-job", cron = "0/10 * * * * ?")
        public void execute() {
        }
    }

    static class GroupJobBean {
        @ScheduleJob(name = "group-job-one", cron = "0/10 * * * * ?")
        public void executeOne() {
        }

        @ScheduleJob(name = "group-job-two", cron = "0/20 * * * * ?")
        public void executeTwo() {
        }
    }
}
