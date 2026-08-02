package com.wly.job.server.client.future;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.server.client.callback.ScheduleCallback;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleFutureTest {

    @Test
    void completeDispatchesOnSuccessWithContextAndResult() throws Exception {
        ScheduleFuture<String> future = new ScheduleFuture<>(1000, null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> resultRef = new AtomicReference<>();
        ScheduleJobRequest request = ScheduleJobRequest.builder().requestId("r1").jobname("j1").build();
        future.addCallback(new ScheduleCallback() {
            @Override
            public void onSuccess(ScheduleCallbackContext context, Object result) {
                resultRef.set(result);
                assertEquals("r1", context.request().getRequestId());
                assertTrue(context.singleRun());
                latch.countDown();
            }

            @Override
            public void onFailure(ScheduleCallbackContext context, Throwable cause) {
            }
        }, new ScheduleCallbackContext(request, 7L, true));

        future.complete("hello");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("hello", resultRef.get());
    }

    @Test
    void completeExceptionallyDispatchesOnFailure() throws Exception {
        ScheduleFuture<String> future = new ScheduleFuture<>(1000, null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> causeRef = new AtomicReference<>();
        future.addCallback(new ScheduleCallback() {
            @Override
            public void onSuccess(ScheduleCallbackContext context, Object result) {
            }

            @Override
            public void onFailure(ScheduleCallbackContext context, Throwable cause) {
                causeRef.set(cause);
                latch.countDown();
            }
        }, new ScheduleCallbackContext(ScheduleJobRequest.builder().requestId("r2").build(), null, false));

        future.completeExceptionally(new IllegalStateException("boom"));
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("boom", causeRef.get().getMessage());
    }

    @Test
    void oneThrowingCallbackDoesNotBreakOthers() throws Exception {
        ScheduleFuture<String> future = new ScheduleFuture<>(1000, null);
        CountDownLatch latch = new CountDownLatch(1);
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("r3").build(), null, false);
        future.addCallback(new ScheduleCallback() {
            @Override
            public void onSuccess(ScheduleCallbackContext context, Object result) {
                throw new IllegalStateException("hook fail");
            }

            @Override
            public void onFailure(ScheduleCallbackContext context, Throwable cause) {
            }
        }, ctx);
        future.addCallback(new ScheduleCallback() {
            @Override
            public void onSuccess(ScheduleCallbackContext context, Object result) {
                latch.countDown();
            }

            @Override
            public void onFailure(ScheduleCallbackContext context, Throwable cause) {
                latch.countDown();
            }
        }, ctx);

        future.complete("x");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }
}
