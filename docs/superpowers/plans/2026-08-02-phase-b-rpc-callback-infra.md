# Phase B: RPC 回调与基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 RPC 回调改为可扩展的 `ScheduleCallback` 注册表机制，回调派发迁入 `ScheduleFuture`，Handler 瘦身并具备优雅关闭，最后完成配置 profile 拆分。

**Architecture:** 新增 `client/callback` 包（接口 + 上下文 + Spring 注册表）；`ScheduleFuture` 在 `complete/completeExceptionally` 内部用专用执行器异步派发回调并做异常隔离；`ScheduleRequestHandler` 只维护 requestId→Future 映射，超时清理改为单线程调度线程池；配置拆分为公共/开发/生产三个文件。

**Tech Stack:** Java 21、Spring Boot 3.5.6、Netty 4.1.108.Final、JUnit 5 + Mockito。

## Global Constraints

- 前置：Phase A 已合入（`SingleRunTracker`、`Job.finished`、异步建连、分片派发）。
- JDK 21，构建：`$env:JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn test`
- 不引入新运行时依赖。
- 回调不得在 Netty I/O 线程执行；单个回调异常不得影响其他回调。
- 每个任务结束时构建必须绿色（B1 与 B2/B3 的耦合见任务内说明，禁止跨任务留下编译失败状态）。

---

### Task B1: ScheduleCallback API + ScheduleFuture 派发 + Handler 瘦身

> 本任务必须一次性完成 B1 的全部内容：`ScheduleFuture` 更换回调 API 后，客户端与 Handler 必须同步迁移，否则构建中断。

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/client/callback/ScheduleCallback.java`
- Create: `admin/src/main/java/com/wly/job/server/client/callback/ScheduleCallbackContext.java`
- Create: `admin/src/main/java/com/wly/job/server/client/callback/ScheduleCallableRegistry.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/future/ScheduleFuture.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java`（`ScheduleResultCallable` 改为实现 `ScheduleCallback`）
- Modify: `admin/src/main/java/com/wly/job/server/client/handler/ScheduleRequestHandler.java`（删除回调派发、清理线程改调度线程池、`completeExceptionally` 不再手动派发）
- Delete: `admin/src/main/java/com/wly/job/server/client/future/ScheduleCallable.java`
- Test: `admin/src/test/java/com/wly/job/server/client/future/ScheduleFutureTest.java`
- Test: `admin/src/test/java/com/wly/job/server/client/handler/ScheduleRequestHandlerTest.java`

**Interfaces:**
- Consumes: Phase A 的 `ScheduleJobClient.send(request, instance, jobId, singleRun)`、`ScheduleResultCallable(recQueue, jobRep, tracker, requestId, jobId, singleRun)`。
- Produces:
  - `ScheduleCallback.onSuccess(ScheduleCallbackContext, Object)` / `onFailure(ScheduleCallbackContext, Throwable)`
  - `ScheduleCallbackContext(ScheduleJobRequest request, Long jobId, boolean singleRun)`
  - `ScheduleFuture.addCallback(ScheduleCallback, ScheduleCallbackContext)`；`ScheduleFuture.shutdown()`
  - `ScheduleRequestHandler.completeExceptionally(String, Throwable)`（只完成 Future）
  - `ScheduleRequestHandler.cleanupExpiredRequests()`（package-private static）

- [ ] **Step 1: 写失败测试**

`ScheduleFutureTest.java`：

```java
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
```

`ScheduleRequestHandlerTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleFutureTest,ScheduleRequestHandlerTest`
Expected: 编译失败（`ScheduleCallback` 不存在、`addCallback` 不存在、`completeExceptionally` 不可见）。

- [ ] **Step 3: 实现**

`ScheduleCallback.java`：

```java
package com.wly.job.server.client.callback;

/**
 * 调度 RPC 结果回调扩展点。
 * 实现类注册为 Spring Bean 后由 {@link ScheduleCallableRegistry} 自动收集；
 * 回调在 {@link com.wly.job.server.client.future.ScheduleFuture} 完成后异步执行，单个回调异常不影响其他回调。
 */
public interface ScheduleCallback {

    void onSuccess(ScheduleCallbackContext context, Object result);

    void onFailure(ScheduleCallbackContext context, Throwable cause);
}
```

`ScheduleCallbackContext.java`：

```java
package com.wly.job.server.client.callback;

import com.wly.job.common.bean.ScheduleJobRequest;

/**
 * 回调上下文：请求体 + 作业维度信息，供监控/审计等扩展回调使用。
 */
public record ScheduleCallbackContext(ScheduleJobRequest request, Long jobId, boolean singleRun) {
}
```

`ScheduleCallableRegistry.java`：

```java
package com.wly.job.server.client.callback;

import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.server.client.future.ScheduleFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回调注册表：Spring 自动收集所有 {@link ScheduleCallback} Bean，按 @Order 排序后附加到每个请求的 Future。
 */
@Component
@RequiredArgsConstructor
public class ScheduleCallableRegistry {

    private final List<ScheduleCallback> callbacks;

    public void attachAll(ScheduleFuture<ScheduleJobResponse> future, ScheduleCallbackContext context) {
        callbacks.forEach(callback -> future.addCallback(callback, context));
    }
}
```

`ScheduleFuture.java` 替换回调相关部分：

```java
package com.wly.job.server.client.future;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.callback.ScheduleCallback;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC异步结果Future：完成后在专用执行器上异步派发回调（异常隔离）。
 */
@Slf4j
public class ScheduleFuture<T> extends CompletableFuture<T> {

    private static final ExecutorService CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "schedule-future-callback");
        thread.setDaemon(true);
        return thread;
    });

    private final long createTime;

    @Setter
    private long timeout;

    @Setter
    @Getter
    private Channel channel;

    private final List<CallbackEntry> callbacks = new CopyOnWriteArrayList<>();

    public ScheduleFuture(long timeout, Channel channel) {
        this.createTime = System.currentTimeMillis();
        this.timeout = timeout;
        this.channel = channel;
    }

    public void addCallback(ScheduleCallback callback, ScheduleCallbackContext context) {
        callbacks.add(new CallbackEntry(callback, context));
    }

    @Override
    public boolean complete(T value) {
        boolean done = super.complete(value);
        if (done) {
            dispatchCallbacks(value, null);
        }
        return done;
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        boolean done = super.completeExceptionally(ex);
        if (done) {
            dispatchCallbacks(null, ex);
        }
        return done;
    }

    private void dispatchCallbacks(T value, Throwable ex) {
        try {
            CALLBACK_EXECUTOR.execute(() -> {
                for (CallbackEntry entry : callbacks) {
                    try {
                        if (ex == null) {
                            entry.callback().onSuccess(entry.context(), value);
                        } else {
                            entry.callback().onFailure(entry.context(), ex);
                        }
                    } catch (Exception e) {
                        log.error("Schedule callback fail, requestId: {}",
                                entry.context().request().getRequestId(), e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Callback executor has been shut down, callbacks skipped.");
        }
    }

    public static void shutdown() {
        CALLBACK_EXECUTOR.shutdown();
        try {
            if (!CALLBACK_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                CALLBACK_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            CALLBACK_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record CallbackEntry(ScheduleCallback callback, ScheduleCallbackContext context) {
    }

    // get() / get(long, TimeUnit) / isTimeout() 保持现状不变
}
```

删除 `ScheduleCallable.java`。

`ScheduleRequestHandler.java`：

- 删除 import：`ScheduleCallable`、`lombok.Setter`、`java.util.List`；
- 删除字段/方法：`cleanupThreadStarted`、`LOCK`、`CALLBACK_EXECUTOR`、`callbackOnSuccess`、`callbackOnFailure`、内部类 `TimeoutCleanupTask`；
- 新增：

```java
    private static final ScheduledExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ScheduleRequestHandler-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean(false);

    private static void startCleanupIfNeeded() {
        if (CLEANUP_STARTED.compareAndSet(false, true)) {
            CLEANUP_EXECUTOR.scheduleWithFixedDelay(
                    ScheduleRequestHandler::cleanupExpiredRequests, 30, 30, TimeUnit.SECONDS);
        }
    }
```

- `put(...)` 中的 `startCleanupThreadIfNeeded()` 改为 `startCleanupIfNeeded()`；
- `complete(...)` 删除两处手动回调派发，改为只 `future.complete(response)` / `future.completeExceptionally(...)`；
- `completeExceptionally(String, Throwable)` 删除手动 `callbackOnFailure`（回调由 `ScheduleFuture` 派发）；
- `cleanupExpiredRequests()` 与 `cleanupAllRequests(Channel)` 删除手动 `callbackOnFailure`；`cleanupExpiredRequests` 改为 package-private static（去掉 `private`，供测试直接调用）；
- 删除 FIXME 注释；
- `shutdown()` 替换为：

```java
    /**
     * 优雅关闭：停止超时清理调度器，并等待在途回调完成（有界 3 秒）
     */
    public static void shutdown() {
        CLEANUP_EXECUTOR.shutdownNow();
        ScheduleFuture.shutdown();
    }
```

`ScheduleJobClient.java`：`ScheduleResultCallable` 改为实现 `ScheduleCallback`（保持 Phase A 行为）：

```java
    public record ScheduleResultCallable(ScheduleRecQueue recQueue, JobRep jobRep, SingleRunTracker tracker,
                                         String requestId, Long jobId, boolean singleRun) implements ScheduleCallback {
        @Override
        public void onSuccess(ScheduleCallbackContext context, Object result) {
            tracker.remove(jobId);
            recQueue.markSuccess(requestId, JSON.toJSONString(result));
            if (singleRun && jobId != null) {
                jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                        .eq(Job::getId, jobId)
                        .eq(Job::getFinished, 0)
                        .set(Job::getFinished, 1));
            }
        }

        @Override
        public void onFailure(ScheduleCallbackContext context, Throwable throwable) {
            tracker.remove(jobId);
            recQueue.markFail(requestId, throwable == null ? null : throwable.getMessage());
        }
    }
```

`send` 中改为：

```java
        future.addCallback(new ScheduleResultCallable(recQueue, jobRep, tracker, requestId, jobId, singleRun),
                new ScheduleCallbackContext(request, jobId, singleRun));
```

同时删除 `send()` 连接失败分支中阶段 A 留下的手工派发（否则会与 `ScheduleFuture` 自动派发形成双重回调）：

```java
                // 删除以下两行（ScheduleFuture 已自动派发失败回调）：
                // future.getCallables().forEach(callable -> callable.onFailure(throwable));
```

> `completeExceptionally` 路径（连接/发送失败）不再手动派发，回调由 `ScheduleFuture.completeExceptionally` 统一触发。

- [ ] **Step 4: 运行测试确认通过 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS（含 NettyRoundTripTest：失败响应路径现在经 `ScheduleFuture` 派发）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/client/callback admin/src/main/java/com/wly/job/server/client/future admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java admin/src/main/java/com/wly/job/server/client/handler/ScheduleRequestHandler.java admin/src/test/java/com/wly/job/server/client/future/ScheduleFutureTest.java admin/src/test/java/com/wly/job/server/client/handler/ScheduleRequestHandlerTest.java
git commit -m "refactor: 回调派发迁入 ScheduleFuture，Handler 瘦身并优雅关闭"
```

---

### Task B2: ScheduleRecCallback Bean + 客户端简化

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/client/callback/ScheduleRecCallback.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java`（record 改为 `(ScheduleProps, ScheduleCallableRegistry)`）
- Delete: `admin/src/test/java/com/wly/job/server/client/ScheduleJobClientTest.java`（行为迁移到 ScheduleRecCallbackTest）
- Test: `admin/src/test/java/com/wly/job/server/client/callback/ScheduleRecCallbackTest.java`

**Interfaces:**
- Consumes: Task B1 的 `ScheduleCallback` / `ScheduleCallbackContext` / `ScheduleCallableRegistry`；Phase A 的 `SingleRunTracker`、`ScheduleRecQueue`。
- Produces: `ScheduleRecCallback`（@Order(0) Bean）；`ScheduleJobClient(ScheduleProps, ScheduleCallableRegistry)`。

- [ ] **Step 1: 写失败测试**

`ScheduleRecCallbackTest.java`：

```java
package com.wly.job.server.client.callback;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScheduleRecCallbackTest {

    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleRecCallback callback = new ScheduleRecCallback(recQueue, jobRep, tracker);

    @Test
    void onSuccessOfSingleRunMarksFinishedAndSuccess() {
        tracker.add(7L);
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, true);

        callback.onSuccess(ctx, "ok");

        assertFalse(tracker.contains(7L));
        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
    }

    @Test
    void onFailureRemovesInFlightAndMarksFail() {
        tracker.add(7L);
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, true);

        callback.onFailure(ctx, new RuntimeException("boom"));

        assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleCallbackContext ctx = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, false);

        callback.onSuccess(ctx, "ok");

        verify(jobRep, never()).update(any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleRecCallbackTest`
Expected: 编译失败（`ScheduleRecCallback` 不存在）。

- [ ] **Step 3: 实现**

`ScheduleRecCallback.java`：

```java
package com.wly.job.server.client.callback;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 内置回调：执行结果回写 ScheduleRec；单次任务成功置 Finished、失败移出 in-flight。
 */
@Component
@RequiredArgsConstructor
@Order(0)
public class ScheduleRecCallback implements ScheduleCallback {

    private final ScheduleRecQueue recQueue;

    private final JobRep jobRep;

    private final SingleRunTracker singleRunTracker;

    @Override
    public void onSuccess(ScheduleCallbackContext context, Object result) {
        singleRunTracker.remove(context.jobId());
        recQueue.markSuccess(context.request().getRequestId(), JSON.toJSONString(result));
        if (context.singleRun() && context.jobId() != null) {
            jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                    .eq(Job::getId, context.jobId())
                    .eq(Job::getFinished, 0)
                    .set(Job::getFinished, 1));
        }
    }

    @Override
    public void onFailure(ScheduleCallbackContext context, Throwable cause) {
        singleRunTracker.remove(context.jobId());
        recQueue.markFail(context.request().getRequestId(), cause == null ? null : cause.getMessage());
    }
}
```

`ScheduleJobClient.java` 整体替换为：

```java
package com.wly.job.server.client;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.client.callback.ScheduleCallbackContext;
import com.wly.job.server.client.callback.ScheduleCallableRegistry;
import com.wly.job.server.client.future.ScheduleFuture;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import com.wly.job.server.config.ScheduleProps;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public record ScheduleJobClient(ScheduleProps props, ScheduleCallableRegistry callbackRegistry) {

    public void send(ScheduleJobRequest request, JobInstance instance, Long jobId, boolean singleRun) {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(props.getReqTimeout(), null);
        String requestId = request.getRequestId();
        callbackRegistry.attachAll(future, new ScheduleCallbackContext(request, jobId, singleRun));
        ChannelManager.getChannelAsync(instance.getHost(), instance.getPort()).whenComplete((channel, throwable) -> {
            if (throwable != null) {
                log.error("Connect schedule instance fail: {}:{}", instance.getHost(), instance.getPort(), throwable);
                future.completeExceptionally(new ScheduleException(
                        "Connect schedule instance fail: " + instance.getHost() + ":" + instance.getPort(), throwable));
                return;
            }
            future.setChannel(channel);
            ScheduleRequestHandler.put(requestId, future, props.getReqTimeout());
            channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Send request fail: ", f.cause());
                    ScheduleRequestHandler.completeExceptionally(
                            requestId, new ScheduleException("Send request fail", f.cause()));
                }
            });
        });
    }
}
```

删除 `ScheduleJobClientTest.java`（原嵌套 callable 的行为已由 `ScheduleRecCallbackTest` 覆盖）。

- [ ] **Step 4: 运行测试确认通过 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS（`ScheduleRecCallback` 为唯一 `ScheduleCallback` Bean，注册表正常装配）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/client/callback/ScheduleRecCallback.java admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java admin/src/test/java/com/wly/job/server/client/callback/ScheduleRecCallbackTest.java admin/src/test/java/com/wly/job/server/client/ScheduleJobClientTest.java
git commit -m "refactor: 内置回写拆为 ScheduleRecCallback Bean，客户端通过注册表附加回调"
```

---

### Task B3: 配置 profile 拆分

**Files:**
- Modify: `admin/src/main/resources/application.yml`（仅保留公共骨架）
- Create: `admin/src/main/resources/application-dev.yml`
- Create: `admin/src/main/resources/application-prod.yml`
- Test: `admin/src/test/java/com/wly/job/server/config/ProfileConfigTest.java`

**Interfaces:**
- Consumes: 现有 `application.yml` 全部内容。
- Produces: 三个配置文件；`schedule.dispatch-threads` 从公共文件移入 profile 文件（值保持 1）。

- [ ] **Step 1: 写失败测试**

`ProfileConfigTest.java`：

```java
package com.wly.job.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileConfigTest {

    private Map<String, Object> load(String file) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(file, new ClassPathResource(file));
        Map<String, Object> props = new HashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    props.put(name, enumerable.getProperty(name));
                }
            }
        }
        return props;
    }

    @Test
    void devProfileContainsBusinessConfig() throws Exception {
        Map<String, Object> props = load("application-dev.yml");
        assertTrue(props.containsKey("schedule.registry"));
        assertTrue(props.containsKey("schedule.dispatch-threads"));
        assertTrue(props.containsKey("spring.datasource.url"));
        assertTrue(props.containsKey("instance-client.serverAddress"));
    }

    @Test
    void prodProfileUsesEnvPlaceholdersWithoutDefaults() throws Exception {
        Map<String, Object> props = load("application-prod.yml");
        assertEquals("${DB_URL}", props.get("spring.datasource.url"));
        assertEquals("${DB_USERNAME}", props.get("spring.datasource.username"));
        assertEquals("${DB_PASSWORD}", props.get("spring.datasource.password"));
        assertTrue(props.containsKey("schedule.dispatch-threads"));
    }

    @Test
    void commonProfileHasNoSecrets() throws Exception {
        Map<String, Object> props = load("application.yml");
        assertTrue(props.containsKey("spring.profiles.active"));
        assertTrue(!props.containsKey("spring.datasource.password"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=ProfileConfigTest`
Expected: 失败（`application-dev.yml` 不存在 / prod 无占位符 / 公共文件含密码）。

- [ ] **Step 3: 实现**

`application.yml` 替换为：

```yaml
spring:
  application:
    name: schedule-admin-server
  profiles:
    active: dev

mybatis-plus:
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
  mapper-locations: classpath:mapper/*.xml
```

`application-dev.yml`：

```yaml
server:
  port: 8100

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/job?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&transformedBitIsBoolean=true&allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: lifan1994

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

logging:
  level:
    com.wly.job.server: debug
    org.mybatis: ERROR
    com.wly.job.server.dao.mapper: ERROR

instance-client:
  serverAddress: http://localhost:8080
  accessToken: defaultToken
  env: dev
  appname: ${spring.application.name}
  port: ${server.port}

schedule:
  registry: DEFAULT
  service: GROUP
  enableRegisterInstance: true
  engine: DELAY_QUEUE
  dispatch-threads: 1

cors:
  allowed-origins: "*"
```

`application-prod.yml`：

```yaml
server:
  port: 8100

spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl

logging:
  level:
    com.wly.job.server: info
    org.mybatis: ERROR
    com.wly.job.server.dao.mapper: ERROR

instance-client:
  serverAddress: ${REGISTRY_CENTER_URL}
  accessToken: ${REGISTRY_ACCESS_TOKEN}
  env: ${REGISTRY_ENV}
  appname: ${spring.application.name}
  port: ${server.port}

schedule:
  registry: DEFAULT
  service: GROUP
  enableRegisterInstance: true
  engine: DELAY_QUEUE
  dispatch-threads: 1

cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:*}
```

> 说明：原 `application.yml` 中的 `schedule.dispatch-threads` 键（Task A3 添加）随本任务移入两个 profile 文件，值不变。

- [ ] **Step 4: 运行测试确认通过 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/resources/application.yml admin/src/main/resources/application-dev.yml admin/src/main/resources/application-prod.yml admin/src/test/java/com/wly/job/server/config/ProfileConfigTest.java
git commit -m "chore: 配置按环境拆分（公共/开发/生产），prod 密钥走环境变量"
```

---

## Self-Review

**Spec 覆盖：**
- Spec #7/#8/#9（回调 API、Future 内派发、异常隔离）→ Task B1。
- Spec #10（优雅关闭、调度线程池清理）→ Task B1（Handler shutdown + CLEANUP_EXECUTOR）。
- Spec #12（profile 拆分）→ Task B3。
- 阶段 B 验收 1-6 全部有对应任务。

**占位符扫描：** 无 TBD/TODO；所有代码步骤均给出完整实现。

**类型一致性：**
- `ScheduleCallbackContext(request, jobId, singleRun)` 在 Task B1 定义，B1/B2 的测试与实现中一致使用。
- `ScheduleFuture.addCallback(ScheduleCallback, ScheduleCallbackContext)` 在 B1 定义，B1 客户端与 B2 注册表一致调用。
- `ScheduleRequestHandler.cleanupExpiredRequests()` 在 B1 测试与实现中均为 package-private static。
- `ScheduleRecCallback(recQueue, jobRep, singleRunTracker)` 构造签名与测试一致。
- `ScheduleJobClient` record 组件顺序：B1 保持 `(props, recQueue, jobRep, tracker)`，B2 变为 `(props, callbackRegistry)`——B2 中同步删除旧测试，无残留引用。
