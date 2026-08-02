# Phase A: 调度语义与派发 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落实单次任务 At-Least-Once 语义（finished 终态、失败按 Cron 重试）、jobId 分片派发、异步建连、cron 编辑校验与死代码清理。

**Architecture:** Job 表新增 `finished` 列承载单次任务终态（与 status 管理态解耦）；`SingleRunTracker` 组件跟踪 in-flight 单次任务；`JobScheduler` 改为"单派发线程 + N 个 jobId 分片 worker"；`ChannelManager` 改为异步建连；编辑接口复用 `CronUtils` 校验。

**Tech Stack:** Java 21、Spring Boot 3.5.6、MyBatis-Plus 3.5.7、Netty 4.1.108.Final、JUnit 5 + Mockito（spring-boot-starter-test）。

## Global Constraints

- JDK 21，构建命令：`$env:JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn test`
- 不引入新运行时依赖；admin 模块可新增 test-scope 的 `com.wly.job:core` 依赖（仅测试用）。
- 业务异常派生自 `ScheduleException`；Netty I/O 线程禁止 DB/网络 I/O。
- 提交前基线：Task 0 先把当前工作区所有未提交改动提交为基线 commit（执行前需用户确认）。
- 每次 commit 前必须通过 `mvn -q -pl <module> -am test`（或全量 `mvn test`）验证。

---

### Task 0: 提交基线

**Files:**
- 无新文件。提交现有全部未提交改动。

**Interfaces:**
- Consumes: 当前工作区所有未提交改动（上一轮审查修复 + 在途引擎抽象）。
- Produces: 干净基线，后续任务可独立 commit。

- [ ] **Step 1: 确认基线范围**

Run: `git status --short`
Expected: 列出 40+ 个已修改/未跟踪文件（AGENTS.md、CONTEXT.md、docs/、admin、common、core 等）。

- [ ] **Step 2: 提交基线**

```bash
git add -A
git commit -m "chore: 基线提交（审查修复与调度引擎抽象在途改动）"
```

> 注意：提交前与用户确认"当前在途改动可以一起提交"。

- [ ] **Step 3: 验证**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

---

### Task A1: Job.finished 字段（实体 / DDL / 响应）

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/dao/entity/Job.java`
- Modify: `admin/src/main/java/com/wly/job/server/pojo/resp/JobResp.java`
- Modify: `admin/src/main/java/com/wly/job/server/convert/JobBeanConverter.java`
- Modify: `docs/sql/schema.sql`
- Test: `admin/src/test/java/com/wly/job/server/convert/JobBeanConverterTest.java`

**Interfaces:**
- Consumes: 现有 `Job` / `JobResp` / `JobBeanConverter`。
- Produces: `Job.getFinished()/setFinished()/isFinished()`、`JobResp.finished`、`Job.init()` 默认 `finished=0`。

- [ ] **Step 1: 写失败测试**

`admin/src/test/java/com/wly/job/server/convert/JobBeanConverterTest.java`：

```java
package com.wly.job.server.convert;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.pojo.resp.JobResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobBeanConverterTest {

    @Test
    void convertMapsFinishedFlag() {
        Job job = Job.builder().id(1L).name("demo").finished(1).build();
        JobResp resp = JobBeanConverter.convert(job);
        assertNotNull(resp);
        assertEquals(1, resp.getFinished());
    }

    @Test
    void initDefaultsFinishedToZero() {
        Job job = Job.builder().build().init();
        assertEquals(0, job.getFinished());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=JobBeanConverterTest`
Expected: 编译失败（`finished` 不存在）。

- [ ] **Step 3: 实现**

`Job.java` 在 `strategy` 字段后增加：

```java
    /**
     * 单次任务完成标记
     * 0: 未完成 1: 已完成（终态，与管理态 status 解耦）
     */
    private Integer finished;
```

`Job.init()` 中增加：

```java
        this.finished = 0;
```

`JobResp.java` 在 `strategyDesc` 后增加：

```java
    private Integer finished;
```

`JobBeanConverter.convert(Job)` 的 builder 增加：

```java
                .finished(job.getFinished())
```

`docs/sql/schema.sql` 的 `job` 表在 `strategy` 列后增加：

```sql
    `finished`      TINYINT      NOT NULL DEFAULT 0 COMMENT '单次任务完成标记: 0未完成 1已完成',
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=JobBeanConverterTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/dao/entity/Job.java admin/src/main/java/com/wly/job/server/pojo/resp/JobResp.java admin/src/main/java/com/wly/job/server/convert/JobBeanConverter.java docs/sql/schema.sql admin/src/test/java/com/wly/job/server/convert/JobBeanConverterTest.java
git commit -m "feat: Job 增加 finished 终态字段（与管理态解耦）"
```

---

### Task A2: SingleRunTracker + 成功置 Finished / 失败移出 in-flight

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/schedule/SingleRunTracker.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/SingleRunTrackerTest.java`
- Test: `admin/src/test/java/com/wly/job/server/client/ScheduleJobClientTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`

**Interfaces:**
- Consumes: Task A1 的 `Job.finished`；现有 `ScheduleRecQueue`、`JobRep`。
- Produces: `SingleRunTracker.add/remove/contains/snapshot`；`JobScheduler.reconcileQueuedJobs()` 改为 package-private（测试可见）；`ScheduleResultCallable(ScheduleRecQueue, JobRep, SingleRunTracker, String, Long, boolean)`。

- [ ] **Step 1: 写失败测试**

`SingleRunTrackerTest.java`：

```java
package com.wly.job.server.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleRunTrackerTest {

    private final SingleRunTracker tracker = new SingleRunTracker();

    @Test
    void addContainsAndRemove() {
        assertFalse(tracker.contains(1L));
        tracker.add(1L);
        assertTrue(tracker.contains(1L));
        tracker.remove(1L);
        assertFalse(tracker.contains(1L));
    }

    @Test
    void nullJobIdIsIgnored() {
        tracker.add(null);
        assertFalse(tracker.contains(null));
        tracker.remove(null);
    }
}
```

`ScheduleJobClientTest.java`：

```java
package com.wly.job.server.client;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScheduleJobClientTest {

    @Test
    void onFailureRemovesInFlightAndMarksFail() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();
        tracker.add(7L);

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, true);
        callable.onFailure(new RuntimeException("boom"));

        org.junit.jupiter.api.Assertions.assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
    }

    @Test
    void onSuccessOfSingleRunMarksFinished() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, true);
        callable.onSuccess("ok");

        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
        JobRep jobRep = mock(JobRep.class);
        SingleRunTracker tracker = new SingleRunTracker();

        var callable = new ScheduleJobClient.ScheduleResultCallable(recQueue, jobRep, tracker,
                "req-1", 7L, false);
        callable.onSuccess("ok");

        verify(jobRep, never()).update(any(), any());
    }
}
```

`JobSchedulerTest.java`（对账逻辑；先只测 A2 相关）：

```java
package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();

    private JobScheduler scheduler() {
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker);
    }

    @Test
    void finishedSingleRunJobIsNeverQueuedAndSweepsInFlight() {
        Job finished = Job.builder().id(1L).name("once").type(1).finished(1).cron("0/5 * * * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(finished), List.of());
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        org.junit.jupiter.api.Assertions.assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        Job job = Job.builder().id(2L).name("every").type(0).finished(0).cron("0/5 * * * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(job), List.of());

        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=SingleRunTrackerTest,ScheduleJobClientTest,JobSchedulerTest`
Expected: 编译失败（`SingleRunTracker` 不存在、`ScheduleResultCallable` 构造签名不同、`reconcileQueuedJobs` 不可见）。

- [ ] **Step 3: 实现**

`SingleRunTracker.java`：

```java
package com.wly.job.server.schedule;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次任务 in-flight 跟踪器（内存态）。
 * 契约：At-Least-Once——Admin 重启后集合丢失，已派发未回执的单次任务可能被重新入队，Worker 必须幂等。
 * 失败回调会显式移除；成功置 Finished 后由对账扫描清扫。
 */
@Component
public class SingleRunTracker {

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public void add(Long jobId) {
        if (jobId != null) {
            inFlight.add(jobId);
        }
    }

    public void remove(Long jobId) {
        if (jobId != null) {
            inFlight.remove(jobId);
        }
    }

    public boolean contains(Long jobId) {
        return jobId != null && inFlight.contains(jobId);
    }

    public Set<Long> snapshot() {
        return Set.copyOf(inFlight);
    }
}
```

`ScheduleJobClient.java`：record 增加 `SingleRunTracker tracker` 参数；`send` 中构造 callable 改为：

```java
        future.addCallable(new ScheduleResultCallable(recQueue, jobRep, tracker, requestId, jobId, singleRun));
```

`ScheduleResultCallable` 替换为：

```java
    public record ScheduleResultCallable(ScheduleRecQueue recQueue, JobRep jobRep, SingleRunTracker tracker,
                                         String requestId, Long jobId, boolean singleRun) implements ScheduleCallable {
        @Override
        public void onSuccess(Object result) {
            tracker.remove(jobId);
            recQueue.markSuccess(requestId, JSON.toJSONString(result));
            // 单次任务成功 -> Finished 终态（与管理态 status 解耦），见 ADR-0003
            if (singleRun && jobId != null) {
                jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                        .eq(Job::getId, jobId)
                        .eq(Job::getFinished, 0)
                        .set(Job::getFinished, 1));
            }
        }

        @Override
        public void onFailure(Throwable throwable) {
            // 失败移出 in-flight，由定时扫描按 Cron 自然重试
            tracker.remove(jobId);
            recQueue.markFail(requestId, throwable == null ? null : throwable.getMessage());
        }
    }
```

`JobScheduler.java`：

- 移除 `Set<Long> singleRunInFlight` 字段，注入 `private final SingleRunTracker singleRunTracker;`
- `reconcileQueuedJobs()` 改为 package-private（去掉 `private`），并替换为：

```java
    void reconcileQueuedJobs() {
        Set<Long> seen = new HashSet<>();
        long offset = 0;
        var jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (Job job : jobs) {
                if (isFinishedSingleRun(job)) {
                    // 终态任务不进 seen：触发队列移除与 in-flight 清扫
                    continue;
                }
                seen.add(job.getId());
                if (singleRunTracker.contains(job.getId())) {
                    // 已出队等待回调，不能再次入队
                    continue;
                }
                queuedJobs.compute(job.getId(), (id, queued) -> {
                    if (queued == null) {
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    if (isMetadataChanged(queued.job(), job)) {
                        schedulerEngine.remove(queued);
                        ScheduleJob scheduleJob = ScheduleJob.of(job);
                        schedulerEngine.add(scheduleJob);
                        return scheduleJob;
                    }
                    return queued;
                });
            }
            offset = jobs.getLast().getId();
            jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        }

        // 清扫：已从 DB 消失（禁用/删除/Finished）的 in-flight 标记
        singleRunTracker.snapshot().forEach(id -> {
            if (!seen.contains(id)) {
                singleRunTracker.remove(id);
            }
        });

        queuedJobs.keySet().removeIf(id -> {
            if (seen.contains(id)) {
                return false;
            }
            ScheduleJob removed = queuedJobs.get(id);
            if (removed != null) {
                schedulerEngine.remove(removed);
            }
            return true;
        });
    }

    private boolean isFinishedSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode()
                && Objects.equals(job.getFinished(), 1);
    }
```

- 出队路径中的 `singleRunInFlight.add(job.getId())` 改为 `singleRunTracker.add(job.getId())`，`queuedJobs.remove(job.getId())` 保持（已在 Task A3 统一为 `handle`）。
- 删除原 FIXME 注释行（in-flight 语义已由 `SingleRunTracker` 的类注释说明）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=SingleRunTrackerTest,ScheduleJobClientTest,JobSchedulerTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/schedule/SingleRunTracker.java admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/test/java/com/wly/job/server/schedule/SingleRunTrackerTest.java admin/src/test/java/com/wly/job/server/client/ScheduleJobClientTest.java admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "feat: 单次任务成功置 Finished、失败移出 in-flight 按 Cron 重试"
```

---

### Task A3: jobId 分片派发

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/config/ScheduleProps.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Modify: `admin/src/main/resources/application.yml`（新增 `schedule.dispatch-threads: 1`，阶段 B 拆 profile 时移入 profile 文件）
- Test: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`（追加）

**Interfaces:**
- Consumes: Task A2 的 `SingleRunTracker` 与对账逻辑。
- Produces: `ScheduleProps.dispatchThreads`（默认 1）；`JobScheduler.workerIndex(Long, int)`（package-private static）；`JobScheduler` 构造参数变为 `(JobRep, ScheduleJobService, SchedulerEngine, SingleRunTracker, ScheduleProps)`。

- [ ] **Step 1: 追加失败测试**

`JobSchedulerTest.java` 追加：

```java
    @Test
    void workerIndexRoutesByJobId() {
        assertEquals(0, JobScheduler.workerIndex(2L, 2));
        assertEquals(1, JobScheduler.workerIndex(3L, 2));
        assertEquals(1, JobScheduler.workerIndex(-1L, 2));
        assertEquals(0, JobScheduler.workerIndex(null, 2));
    }
```

同时把测试里 `new JobScheduler(jobRep, scheduleJobService, engine, tracker)` 改为 `new JobScheduler(jobRep, scheduleJobService, engine, tracker, props())`，其中：

```java
    private com.wly.job.server.config.ScheduleProps props() {
        com.wly.job.server.config.ScheduleProps props = new com.wly.job.server.config.ScheduleProps();
        props.setDispatchThreads(1);
        return props;
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=JobSchedulerTest`
Expected: 编译失败（`dispatchThreads` / `workerIndex` 不存在）。

- [ ] **Step 3: 实现**

`ScheduleProps.java` 增加：

```java
    /**
     * 调度派发 worker 线程数（按 jobId 分片），默认 1
     */
    private int dispatchThreads = 1;
```

`application.yml` 的 `schedule:` 段增加：

```yaml
  dispatch-threads: 1
```

`JobScheduler.java`：

- 构造参数增加 `private final ScheduleProps scheduleProps;`
- 替换原 `scheduleJobsExecutor` 字段与 `asyncScheduleJobs()`：

```java
    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    private void asyncScheduleJobs() {
        int threads = Math.max(1, scheduleProps.getDispatchThreads());
        dispatchExecutor = Executors.newSingleThreadExecutor(r -> namedThread("job-scheduler-dispatch", r));
        scheduleWorkers = new ExecutorService[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            scheduleWorkers[i] = Executors.newSingleThreadExecutor(
                    r -> namedThread("job-scheduler-worker-" + index, r));
        }
        dispatchExecutor.execute(() -> {
            while (running || !schedulerEngine.isEmpty()) {
                try {
                    ScheduleJob scheduleJob = schedulerEngine.take();
                    scheduleWorkers[workerIndex(scheduleJob.job().getId(), threads)]
                            .execute(() -> handle(scheduleJob));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    static int workerIndex(Long jobId, int threads) {
        return Math.floorMod(jobId == null ? 0 : jobId, threads);
    }

    private static Thread namedThread(String name, Runnable r) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    private void handle(ScheduleJob scheduleJob) {
        try {
            Job job = scheduleJob.job();
            queuedJobs.remove(job.getId(), scheduleJob);
            if (isSingleRun(job)) {
                singleRunTracker.add(job.getId());
            } else {
                requeue(job);
            }
            scheduleJobService.schedule(job);
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
        }
    }
```

- 删除原 `asyncScheduleJobs` 中单线程 take 循环与 FIXME 注释。
- `stop()` 中增加：

```java
        shutdownGracefully(dispatchExecutor);
        if (scheduleWorkers != null) {
            for (ExecutorService worker : scheduleWorkers) {
                shutdownGracefully(worker);
            }
        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=JobSchedulerTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/config/ScheduleProps.java admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/main/resources/application.yml admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "feat: 调度派发改为 jobId 分片线程池（默认单线程，可配置）"
```

---

### Task A4: 异步建连 + Netty 回环集成测试

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/client/ChannelManager.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/handler/ScheduleRequestHandler.java`（新增 `completeExceptionally` 辅助方法，阶段 B 会再瘦身）
- Modify: `admin/pom.xml`（test-scope 依赖 core）
- Test: `admin/src/test/java/com/wly/job/server/client/NettyRoundTripTest.java`

**Interfaces:**
- Consumes: `JobBootstrap` / `JobInstanceHandler` / `DefaultInnerJobRegistry` / `InnerJob`（core 模块，test-scope）。
- Produces: `ChannelManager.getChannelAsync(String, Integer) -> CompletableFuture<Channel>`；`ScheduleRequestHandler.completeExceptionally(String, Throwable)`；`ScheduleJobClient.send` 全异步（不再同步等待连接）。

- [ ] **Step 1: 写失败测试**

`admin/pom.xml` 增加：

```xml
        <dependency>
            <groupId>com.wly.job</groupId>
            <artifactId>core</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```

`NettyRoundTripTest.java`：

```java
package com.wly.job.server.client;

import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.bean.ScheduleJobResponse;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.bootstrap.JobBootstrap;
import com.wly.job.core.bootstrap.JobInstanceHandler;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.registry.DefaultInnerJobRegistry;
import com.wly.job.server.client.future.ScheduleFuture;
import com.wly.job.server.client.handler.ScheduleRequestHandler;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRoundTripTest {

    private JobBootstrap bootstrap;
    private int port;
    private Channel channel;

    @BeforeEach
    void startWorker() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DefaultInnerJobRegistry registry = new DefaultInnerJobRegistry();
        registry.register(new InnerJob() {
            @Override
            public ScheduleJobResponse execute(ScheduleJobRequest request) {
                return ScheduleJobResponse.builder()
                        .success(true).result("ok").requestId(request.getRequestId()).build();
            }

            @Override
            public String jobname() {
                return "echo";
            }
        });
        bootstrap = new JobBootstrap(port, new JobInstanceHandler(registry));
        bootstrap.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (channel == null && System.currentTimeMillis() < deadline) {
            try {
                channel = ChannelManager.getChannelAsync("127.0.0.1", port).get(200, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                Thread.sleep(50);
            }
        }
        assertNotNull(channel, "worker 未在 5s 内就绪");
    }

    @AfterEach
    void tearDown() {
        if (channel != null && channel.isActive()) {
            ChannelManager.removeChannel(channel);
        }
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @AfterAll
    static void releaseStaticResources() {
        ChannelManager.shutdown();
    }

    @Test
    void successRoundTrip() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel);
        ScheduleRequestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("echo").build()).sync();

        ScheduleJobResponse response = future.get(3, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());
        assertEquals(requestId, response.getRequestId());
        assertEquals("ok", response.getResult());
    }

    @Test
    void unknownJobReturnsFailure() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(3000, channel);
        ScheduleRequestHandler.put(requestId, future, 3000);
        channel.writeAndFlush(ScheduleJobRequest.builder().requestId(requestId).jobname("missing").build()).sync();

        ScheduleException ex = assertThrows(ScheduleException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("No such job"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=NettyRoundTripTest`
Expected: 编译失败（`getChannelAsync` 不存在）。

- [ ] **Step 3: 实现**

`ChannelManager.java`：删除 `CHANNEL_MAP` 与 `getChannel`，替换为：

```java
    private static final ConcurrentMap<String, CompletableFuture<Channel>> CHANNEL_FUTURE_MAP = new ConcurrentHashMap<>();

    public static CompletableFuture<Channel> getChannelAsync(String host, Integer port) {
        String key = host + ":" + port;
        return CHANNEL_FUTURE_MAP.computeIfAbsent(key, k -> {
            CompletableFuture<Channel> future = new CompletableFuture<>();
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(EVENTLOOPGROUP)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new JsonDecoder(ScheduleJobResponse.class));
                            pipeline.addLast(new JsonEncoder(ScheduleJobRequest.class));
                            pipeline.addLast(HANDLER);
                        }
                    })
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    Channel channel = f.channel();
                    CHANNEL_WRAPPER_MAP.put(channel.id().asLongText(), new ChannelWrapper(channel, key));
                    future.complete(channel);
                } else {
                    CHANNEL_FUTURE_MAP.remove(key, future);
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        });
    }
```

`removeChannel` 与 `shutdown` 中的 `CHANNEL_MAP` 改为 `CHANNEL_FUTURE_MAP`：

```java
    public static void removeChannel(Channel channel) {
        ChannelWrapper channelWrapper = CHANNEL_WRAPPER_MAP.remove(channel.id().asLongText());
        if (channelWrapper != null) {
            channelWrapper.getChannel().close();
            CHANNEL_FUTURE_MAP.remove(channelWrapper.getChannelKey());
        }
    }

    public static void shutdown() {
        CHANNEL_WRAPPER_MAP.values().forEach(wrapper -> wrapper.getChannel().close());
        CHANNEL_WRAPPER_MAP.clear();
        CHANNEL_FUTURE_MAP.clear();
        EVENTLOOPGROUP.shutdownGracefully();
    }
```

删除不再使用的 `ScheduleException` import。

`ScheduleRequestHandler.java` 新增（阶段 B 会保留并瘦身）：

```java
    /**
     * 移除并异常完成请求（发送失败等场景）
     */
    public static void completeExceptionally(String requestId, Throwable cause) {
        ScheduleFuture<ScheduleJobResponse> future = REQUEST_MAP.remove(requestId);
        TIMEOUT_MAP.remove(requestId);
        if (future != null) {
            removeReqId(requestId, future);
            future.completeExceptionally(cause);
            ScheduleException exception = cause instanceof ScheduleException se ? se
                    : new ScheduleException(cause.getMessage(), cause);
            callbackOnFailure(future.getCallables(), exception);
        }
    }
```

`ScheduleJobClient.java` 的 `send` 替换为：

```java
    public void send(ScheduleJobRequest request, JobInstance instance, Long jobId, boolean singleRun) {
        ScheduleFuture<ScheduleJobResponse> future = new ScheduleFuture<>(props.getReqTimeout(), null);
        String requestId = request.getRequestId();
        future.addCallable(new ScheduleResultCallable(recQueue, jobRep, tracker, requestId, jobId, singleRun));
        ChannelManager.getChannelAsync(instance.getHost(), instance.getPort()).whenComplete((channel, throwable) -> {
            if (throwable != null) {
                log.error("Connect schedule instance fail: {}:{}", instance.getHost(), instance.getPort(), throwable);
                future.completeExceptionally(new ScheduleException(
                        "Connect schedule instance fail: " + instance.getHost() + ":" + instance.getPort(), throwable));
                // 阶段A过渡：连接失败时手工派发失败回调，保证 ScheduleRec 置 FAIL
                // 阶段B起由 ScheduleFuture.completeExceptionally 统一派发，本行将删除
                future.getCallables().forEach(callable -> callable.onFailure(throwable));
                return;
            }
            future.setChannel(channel);
            ScheduleRequestHandler.put(requestId, future, props.getReqTimeout());
            channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("Send request fail: ", f.cause());
                    ScheduleRequestHandler.completeExceptionally(requestId, new ScheduleException("Send request fail", f.cause()));
                }
            });
        });
    }
```

> 说明：阶段 A 中 `completeExceptionally` 手动触发失败回调是为了保证 ScheduleRec 置 FAIL；阶段 B 将回调派发移入 `ScheduleFuture` 后，此方法只完成 Future。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=NettyRoundTripTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 全量回归 + Commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

```bash
git add admin/src/main/java/com/wly/job/server/client/ChannelManager.java admin/src/main/java/com/wly/job/server/client/ScheduleJobClient.java admin/src/main/java/com/wly/job/server/client/handler/ScheduleRequestHandler.java admin/pom.xml admin/src/test/java/com/wly/job/server/client/NettyRoundTripTest.java
git commit -m "feat: 客户端异步建连并新增 Netty 回环集成测试"
```

---

### Task A5: Cron 单次解析 + 编辑校验 + 死代码清理

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/utils/CronUtils.java`
- Modify: `admin/src/main/java/com/wly/job/server/service/JobService.java`
- Modify: `admin/src/main/java/com/wly/job/server/controller/JobController.java`
- Modify: `admin/src/main/java/com/wly/job/server/registry/RemoteRegisterCenterRegistry.java`
- Delete: `admin/src/main/java/com/wly/job/server/registry/LocalCacheJobInstanceRegistry.java`
- Delete: `admin/src/main/java/com/wly/job/server/registry/PersistJobInstanceRegistry.java`
- Test: `admin/src/test/java/com/wly/job/server/utils/CronUtilsTest.java`（追加）
- Test: `admin/src/test/java/com/wly/job/server/service/JobServiceTest.java`（新建）

**Interfaces:**
- Consumes: 现有 `CronUtils.checkCronExpression`、`JobBeanConverter.convert(EditJobReq)`。
- Produces: `JobService.edit(EditJobReq)`（含 cron 校验）。

- [ ] **Step 1: 写失败测试**

`CronUtilsTest.java` 追加：

```java
    @Test
    void invalidCronThrowsScheduleException() {
        assertThrows(com.wly.job.common.exception.ScheduleException.class,
                () -> CronUtils.getNextExecution("not-a-cron"));
    }
```

`JobServiceTest.java`：

```java
package com.wly.job.server.service;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.pojo.req.EditJobReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobServiceTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final JobService jobService = new JobService(jobRep, scheduleJobService);

    @Test
    void editWithInvalidCronRejected() {
        EditJobReq req = EditJobReq.builder().id(1L).cron("bad-cron").build();
        assertThrows(ScheduleException.class, () -> jobService.edit(req));
    }

    @Test
    void editWithValidCronUpdates() {
        EditJobReq req = EditJobReq.builder().id(1L).cron("0/5 * * * * ?").build();
        assertDoesNotThrow(() -> jobService.edit(req));
        verify(jobRep).updateById(any(Job.class));
    }

    @Test
    void editWithoutCronSkipsValidation() {
        EditJobReq req = EditJobReq.builder().id(1L).description("only desc").build();
        assertDoesNotThrow(() -> jobService.edit(req));
        verify(jobRep).updateById(any(Job.class));
    }
}
```

> `JobService` 是 record，构造参数 `(JobRep, ScheduleJobService)`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=CronUtilsTest,JobServiceTest`
Expected: 编译失败（`JobService.edit` 不存在；`getNextExecution("not-a-cron")` 当前抛 IllegalArgumentException 而非 ScheduleException）。

- [ ] **Step 3: 实现**

`CronUtils.getNextExecution(String, LocalDateTime)` 替换为：

```java
    public static LocalDateTime getNextExecution(String cron, LocalDateTime baseTime) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime next = expression.next(baseTime);
            if (next == null) {
                throw new ScheduleException("No next execution time found for cron: " + cron);
            }
            return next;
        } catch (IllegalArgumentException e) {
            throw new ScheduleException("CronExpression parse fail: " + cron, e);
        }
    }
```

删除 Javadoc 中的 FIXME 注释；`checkCronExpression` 保持不变（注册/编辑校验用）。

`JobService.java` 增加：

```java
    public void edit(EditJobReq req) {
        if (StringUtils.hasText(req.getCron())) {
            CronUtils.checkCronExpression(req.getCron());
        }
        jobRep.updateById(JobBeanConverter.convert(req));
    }
```

需要的 import：`com.wly.job.server.utils.CronUtils`。

`JobController.edit` 改为：

```java
    @PostMapping("/edit")
    public Result<Void> edit(@RequestBody EditJobReq req) {
        jobService.edit(req);
        return Result.success();
    }
```

`RemoteRegisterCenterRegistry.unregister` 替换为：

```java
    @Override
    public void unregister(JobInstance jobInstance) {
        // 注册中心（RegistryClient）未提供下线能力，保持空实现；实例依赖心跳过期自动失效
    }
```

删除文件：

```bash
git rm admin/src/main/java/com/wly/job/server/registry/LocalCacheJobInstanceRegistry.java admin/src/main/java/com/wly/job/server/registry/PersistJobInstanceRegistry.java
```

> 删除前先 `rg -n "LocalCacheJobInstanceRegistry|PersistJobInstanceRegistry" --glob "*.java"` 确认无引用。

- [ ] **Step 4: 运行测试确认通过 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS（含 NettyRoundTripTest 与既有测试）。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: cron 单次解析、编辑接口校验、清理僵尸 Registry 类"
```

---

## Self-Review

**Spec 覆盖：**
- Spec #1/#2/#3/#4（At-Least-Once、失败重试、finished 终态、手动补跑）→ Task A1 + A2。
- Spec #5/#6（分片派发、异步建连）→ Task A3 + A4。
- Spec #11（Cron 单次解析）、#16（编辑校验）、#14（死代码）→ Task A5。
- Spec #15（Netty 回环测试）→ Task A4。
- 手动补跑（Spec #4）无需代码改动：`/exec` 直接派发，不经过对账；成功回调置 finished 幂等。

**占位符扫描：** 无 TBD/TODO/“适当处理”类占位；所有代码步骤均给出完整代码。

**类型一致性：**
- `ScheduleResultCallable` 构造签名 `(ScheduleRecQueue, JobRep, SingleRunTracker, String, Long, boolean)` 在 Task A2/A4 中一致。
- `JobScheduler` 构造参数在 Task A2 为 4 参、Task A3 变为 5 参，Task A3 同步更新测试。
- `workerIndex(Long, int)` 在 Task A3 定义与测试一致。
- `Job.finished` 字段名在 Task A1/A2/A5 的实体、Resp、Converter、SQL 中一致。
