package com.wly.job.server.client.handler;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.future.ScheduleFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleRequestHandlerTest {

    @AfterEach
    void clean() {
        ScheduleRequestHandler.getRequestMapSnapshot().keySet().forEach(ScheduleRequestHandler::remove);
    }

    @Test
    void completeCompletesFuture() throws Exception {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null);
        ScheduleRequestHandler.put("req-1", future, 1000);

        ScheduleRequestHandler.complete("req-1", ScheduleJobResponse.builder()
                .requestId("req-1").success(true).result("ok").build());

        assertTrue(future.isDone());
        assertEquals("ok", future.get(1, TimeUnit.SECONDS).getResult());
    }

    @Test
    void completeExceptionallyRemovesAndCompletes() {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null);
        ScheduleRequestHandler.put("req-1", future, 1000);

        ScheduleRequestHandler.completeExceptionally("req-1", new IllegalStateException("boom"));

        assertTrue(future.isCompletedExceptionally());
        assertNull(ScheduleRequestHandler.get("req-1"));
    }

    @Test
    void cleanupExpiredRequestsCompletesExpiredFuture() {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(1000, null);
        ScheduleRequestHandler.put("req-expired", future, -1);

        ScheduleRequestHandler.cleanupExpiredRequests();

        assertTrue(future.isCompletedExceptionally());
        assertNull(ScheduleRequestHandler.get("req-expired"));
    }
}
