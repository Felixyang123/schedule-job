package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.future.ScheduleFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleRequestHandlerTest {

    private ScheduleRequestHandler handler;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        handler = new ScheduleRequestHandler();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-callback");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void clean() {
        handler.shutdown();
        executor.shutdownNow();
    }

    @Test
    void completeCompletesFuture() throws Exception {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null, executor);
        handler.put("req-1", future, 1000);

        handler.complete("req-1", ScheduleJobResponse.builder()
                .requestId("req-1").success(true).result("ok").build());

        assertTrue(future.isDone());
        assertEquals("ok", future.get(1, TimeUnit.SECONDS).getResult());
    }

    @Test
    void completeExceptionallyRemovesAndCompletes() {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null, executor);
        handler.put("req-1", future, 1000);

        handler.completeExceptionally("req-1", new IllegalStateException("boom"));

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void cleanupExpiredRequestsCompletesExpiredFuture() {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null, executor);
        handler.put("req-expired", future, -1);

        handler.cleanupExpiredRequests();

        assertTrue(future.isCompletedExceptionally());
    }
}
