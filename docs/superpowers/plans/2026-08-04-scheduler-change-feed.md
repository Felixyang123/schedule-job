# 调度队列构建可扩展性改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `JobScheduler` 的每秒全量扫描对账改造为变更源（`job_change`）增量对账，队列只持轻量投影，主备切换清理降为 O(n)，并落地删除接口，解决 `asyncBuildScheduleJobs` 的 FIXME。

**Architecture:** 所有作业元数据写路径（注册/编辑/启停/单次完成/删除/失败重试）与 Job 行写入同事务地追加一条 `job_change` 记录；主节点每秒按 id 水印消费、回查当前行后与内存队列 diff（幂等）；成为主节点时全量对账并把水印置为 `max(id)`，另保留 60s 周期全量兜底对账。队列与 `queuedJobs` 只持 `JobView` 轻量投影；`SchedulerEngine` 增加 `clear()` 供主备切换使用。

**Tech Stack:** Java 21 / Spring Boot 3.5.6 / MyBatis-Plus 3.5.7 / Netty 4.1.108.Final / JUnit 5 + Mockito。

## Global Constraints

- JDK 21；Spring Boot 3.5.6；Netty 4.1.108.Final；MyBatis-Plus 3.5.7；**不引入新的运行时依赖**。
- 构建验证：`.\mvnw.cmd test`（`JAVA_HOME=C:\Users\wangyang\.jdks\ms-21.0.10`）。
- 逻辑删除使用 MyBatis-Plus `removeById`（自动 `deleted=1`）。
- 回调与消费不得在 Netty I/O 线程执行（沿用既有回调执行器）。
- 变更记录仅作排查用途，消费端以回查当前行为准（幂等）。
- `finished=1 ⇒ type=1`；type 改为普通任务时同事务重置 finished=0；成功回调置位加 `type=SINGLE` 守卫。
- 在途（in-flight）任务必不在队列；消费端在途时跳过整条变更记录的应用。
- 本计划在 `dev` 分支执行，不新建分支。

## File Map

- Create: `admin/src/main/java/com/wly/job/server/enumeration/JobChangeTypeEnum.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/JobChange.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/mapper/JobChangeMapper.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/rep/JobChangeRep.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/JobView.java`
- Create tests: `JobChangeRepTest` / `JobViewTest` / `DelayQueueSchedulerEngineTest` / `TimeWheelSchedulerEngineTest` / `ScheduleJobServiceTest`
- Modify: `common/.../timewheel/TimeWheel.java`、`schedule/engine/*`、`schedule/ScheduleJob.java`、`schedule/JobScheduler.java`、`dao/rep/JobRep.java`、`service/ScheduleJobService.java`、`service/JobService.java`、`controller/JobController.java`、`client/callback/ScheduleRecCallback.java`、`schedule/ScheduleRunRecovery.java`
- Modify tests: `JobSchedulerTest` / `JobServiceTest` / `ScheduleRecCallbackTest` / `ScheduleRunRecoveryTest` / `common TimeWheelTest`

`docs/sql/schema.sql` 已在上一提交包含 `job_change` 建表与存量修复 SQL，本计划不再改动。

---

### Task 1: 变更源基础设施（枚举 / 实体 / Mapper / Rep）

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/enumeration/JobChangeTypeEnum.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/JobChange.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/mapper/JobChangeMapper.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/rep/JobChangeRep.java`
- Test: `admin/src/test/java/com/wly/job/server/dao/rep/JobChangeRepTest.java`

**Interfaces:**
- Produces: `JobChangeTypeEnum.{REGISTER(1), EDIT(2), SWITCH(3), FINISHED(4), DELETE(5), REQUEUE(6)}`，`getCode(): int`
- Produces: `JobChangeRep.record(Long jobId, Integer changeType, String operator, String requestId, String jobName)`
- Produces: `JobChangeRep.listAfter(long watermark, int limit): List<JobChange>`
- Produces: `JobChangeRep.maxId(): long`
- Produces: `JobChangeRep.deleteUpTo(long watermark): int`

- [ ] **Step 1: Write the failing test**

`admin/src/test/java/com/wly/job/server/dao/rep/JobChangeRepTest.java`：

```java
package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.mapper.JobChangeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobChangeRepTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), JobChange.class);
    }

    private final JobChangeMapper mapper = mock(JobChangeMapper.class);
    private final JobChangeRep rep = new JobChangeRep(mapper);

    private static JobChange change(long id) {
        JobChange c = new JobChange();
        c.setId(id);
        c.setJobId(7L);
        return c;
    }

    @Test
    void recordInsertsAllFields() {
        rep.record(7L, 6, "system", "req-1", "job-a");

        ArgumentCaptor<JobChange> captor = ArgumentCaptor.forClass(JobChange.class);
        verify(mapper).insert(captor.capture());
        JobChange change = captor.getValue();
        assertEquals(7L, change.getJobId());
        assertEquals(6, change.getChangeType());
        assertEquals("system", change.getOperator());
        assertEquals("req-1", change.getRequestId());
        assertEquals("job-a", change.getJobName());
    }

    @Test
    void listAfterQueriesAboveWatermarkOrderedAsc() {
        when(mapper.selectList(any())).thenReturn(List.of(change(11L), change(12L)));

        List<JobChange> result = rep.listAfter(10L, 500);

        assertEquals(2, result.size());
        verify(mapper).selectList(any());
    }

    @Test
    void maxIdReturnsLastIdOrZero() {
        when(mapper.selectOne(any())).thenReturn(change(42L));
        assertEquals(42L, rep.maxId());

        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(0L, rep.maxId());
    }

    @Test
    void deleteUpToRemovesConsumedRows() {
        when(mapper.delete(any())).thenReturn(3);
        assertEquals(3, rep.deleteUpTo(500L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobChangeRepTest -DfailIfNoTests=false`

Expected: 编译失败（`JobChangeRep` / `JobChange` / `JobChangeMapper` / `JobChangeTypeEnum` 不存在）。

- [ ] **Step 3: Implement the four classes**

`admin/src/main/java/com/wly/job/server/enumeration/JobChangeTypeEnum.java`：

```java
package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum JobChangeTypeEnum {
    REGISTER(1),
    EDIT(2),
    SWITCH(3),
    FINISHED(4),
    DELETE(5),
    REQUEUE(6);

    private final int code;
}
```

`admin/src/main/java/com/wly/job/server/dao/entity/JobChange.java`：

```java
package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "job_change")
public class JobChange {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Integer changeType;

    private String operator;

    private String requestId;

    private String jobName;

    private Date createTime;
}
```

`admin/src/main/java/com/wly/job/server/dao/mapper/JobChangeMapper.java`：

```java
package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.JobChange;

public interface JobChangeMapper extends BaseMapper<JobChange> {
}
```

`admin/src/main/java/com/wly/job/server/dao/rep/JobChangeRep.java`：

```java
package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.mapper.JobChangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JobChangeRep {

    private final JobChangeMapper mapper;

    public void record(Long jobId, Integer changeType, String operator, String requestId, String jobName) {
        JobChange change = new JobChange();
        change.setJobId(jobId);
        change.setChangeType(changeType);
        change.setOperator(operator);
        change.setRequestId(requestId);
        change.setJobName(jobName);
        change.setCreateTime(new Date());
        mapper.insert(change);
    }

    public List<JobChange> listAfter(long watermark, int limit) {
        return mapper.selectList(Wrappers.<JobChange>lambdaQuery()
                .gt(JobChange::getId, watermark)
                .orderByAsc(JobChange::getId)
                .last("LIMIT " + limit));
    }

    public long maxId() {
        JobChange last = mapper.selectOne(Wrappers.<JobChange>lambdaQuery()
                .select(JobChange::getId)
                .orderByDesc(JobChange::getId)
                .last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    public int deleteUpTo(long watermark) {
        return mapper.delete(Wrappers.<JobChange>lambdaQuery()
                .le(JobChange::getId, watermark));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobChangeRepTest -DfailIfNoTests=false`

Expected: PASS（4 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/enumeration/JobChangeTypeEnum.java admin/src/main/java/com/wly/job/server/dao/entity/JobChange.java admin/src/main/java/com/wly/job/server/dao/mapper/JobChangeMapper.java admin/src/main/java/com/wly/job/server/dao/rep/JobChangeRep.java admin/src/test/java/com/wly/job/server/dao/rep/JobChangeRepTest.java
git commit -m "feat: 作业变更源基础设施（JobChange/枚举/仓储）"
```

---

### Task 2: 轻量投影（JobView / ScheduleJob / 投影查询 / JobScheduler 类型改造）

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/JobView.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/ScheduleJob.java`
- Modify: `admin/src/main/java/com/wly/job/server/dao/rep/JobRep.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Test: `admin/src/test/java/com/wly/job/server/dao/entity/JobViewTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`

**Interfaces:**
- Produces: `JobView(Long id, String name, String cron, String executeParam, Integer strategy, Integer type)`，`JobView.of(Job): JobView`，`JobView.toJob(): Job`
- Produces: `ScheduleJob(JobView job, long expireNanos)`，`ScheduleJob.of(JobView): ScheduleJob`
- Produces: `JobRep.batchQueryJobViewsByCursor(long cursor, int limit): List<JobView>`

- [ ] **Step 1: Write the failing tests**

`admin/src/test/java/com/wly/job/server/dao/entity/JobViewTest.java`：

```java
package com.wly.job.server.dao.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JobViewTest {

    @Test
    void ofCopiesOnlyProjectionFields() {
        Job job = Job.builder()
                .id(1L).name("j").cron("0/5 * * * * ?").executeParam("p")
                .strategy(2).type(1).description("desc").status(1).finished(0)
                .creator("u").build();

        JobView view = JobView.of(job);

        assertEquals(1L, view.id());
        assertEquals("j", view.name());
        assertEquals("0/5 * * * * ?", view.cron());
        assertEquals("p", view.executeParam());
        assertEquals(2, view.strategy());
        assertEquals(1, view.type());

        Job roundTrip = view.toJob();
        assertEquals(1L, roundTrip.getId());
        assertEquals("j", roundTrip.getName());
        assertEquals("p", roundTrip.getExecuteParam());
        assertNull(roundTrip.getDescription());
        assertNull(roundTrip.getCreator());
    }
}
```

更新 `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`（保留 `workerIndexRoutesByJobId` 原样，其余按投影改造）：

```java
package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);
    private final ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);

    private JobScheduler scheduler() {
        when(leaderElector.isLeader()).thenReturn(true);
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery);
    }

    private ScheduleProps props() {
        ScheduleProps props = new ScheduleProps();
        props.setDispatchThreads(1);
        return props;
    }

    private static Job job(long id, String name, int type, int finished) {
        return Job.builder().id(id).name(name).type(type).finished(finished).cron("0/5 * * * * ?").build();
    }

    @Test
    void finishedSingleRunNeverReturnedByQueryAndSweepsInFlight() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(List.of(), List.of());
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());

        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }

    @Test
    void inFlightSingleRunIsNotRequeuedByFullReconcile() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(3L, "once", 1, 0))), List.of());
        JobScheduler scheduler = scheduler();
        tracker.add(3L);

        scheduler.reconcileQueuedJobs();

        verify(engine, never()).add(any());
    }

    @Test
    void workerIndexRoutesByJobId() {
        assertEquals(0, JobScheduler.workerIndex(2L, 2));
        assertEquals(1, JobScheduler.workerIndex(3L, 2));
        assertEquals(1, JobScheduler.workerIndex(-1L, 2));
        assertEquals(0, JobScheduler.workerIndex(null, 2));
    }

    @Test
    void maybeReconcileSkipsWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.maybeReconcile();

        verify(jobRep, never()).batchQueryJobViewsByCursor(anyLong(), anyInt());
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.handle(ScheduleJob.of(JobView.of(job(1L, "once", 0, 0))));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void loseLeadershipClearsTrackerAndStopsEngine() {
        tracker.add(1L);

        scheduler().onLoseLeadership();

        verify(engine).stop();
        assertFalse(tracker.contains(1L));
    }

    @Test
    void becomeLeaderStartsEngineAndReconciles() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(List.of());

        scheduler().onBecomeLeader();

        verify(engine).start();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobViewTest,JobSchedulerTest -DfailIfNoTests=false`

Expected: 编译失败（`JobView` 不存在、`ScheduleJob.of(JobView)` 不存在、`batchQueryJobViewsByCursor` 不存在）。

- [ ] **Step 3: Implement JobView / ScheduleJob / JobRep**

`admin/src/main/java/com/wly/job/server/dao/entity/JobView.java`：

```java
package com.wly.job.server.dao.entity;

public record JobView(Long id, String name, String cron, String executeParam,
                      Integer strategy, Integer type) {

    public static JobView of(Job job) {
        return new JobView(job.getId(), job.getName(), job.getCron(),
                job.getExecuteParam(), job.getStrategy(), job.getType());
    }

    public Job toJob() {
        return Job.builder()
                .id(id)
                .name(name)
                .cron(cron)
                .executeParam(executeParam)
                .strategy(strategy)
                .type(type)
                .build();
    }
}
```

`admin/src/main/java/com/wly/job/server/schedule/ScheduleJob.java` 全文替换为：

```java
package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.utils.CronUtils;

import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public record ScheduleJob(JobView job, long expireNanos) implements Delayed {
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expireNanos - getNanos(), TimeUnit.NANOSECONDS);
    }

    private long getNanos() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    @Override
    public int compareTo(Delayed o) {
        if (o == this) {
            return 0;
        }
        if (o instanceof ScheduleJob other) {
            long diff = expireNanos - other.expireNanos;
            if (diff < 0) {
                return -1;
            } else if (diff > 0) {
                return 1;
            } else {
                return 0;
            }
        }
        long d = (getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS));
        return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
    }

    public static ScheduleJob of(JobView job) {
        return new ScheduleJob(job, CronUtils.getNextExecutionNanos(job.cron()));
    }
}
```

`admin/src/main/java/com/wly/job/server/dao/rep/JobRep.java` 增加方法（保留 `batchQueryJobsByCursor` 原样）：

```java
public List<JobView> batchQueryJobViewsByCursor(long cursor, int limit) {
    return list(Wrappers.<Job>lambdaQuery()
            .select(Job::getId, Job::getName, Job::getCron, Job::getExecuteParam, Job::getStrategy, Job::getType)
            .eq(Job::getStatus, Job.ENABLE)
            .eq(Job::getFinished, 0)
            .gt(Job::getId, cursor)
            .last("LIMIT " + limit))
            .stream()
            .map(JobView::of)
            .toList();
}
```

并在文件头部增加 `import com.wly.job.server.dao.entity.JobView;`。

- [ ] **Step 4: 改造 JobScheduler 使用 JobView**

`admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java` 全文替换为：

```java
package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.ha.LeadershipListener;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobScheduler implements SmartLifecycle, LeadershipListener {

    private static final long BUILD_SCAN_INTERVAL_MS = 1000L;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps scheduleProps;

    private final ScheduleLeaderElector leaderElector;

    private final ScheduleRunRecovery scheduleRunRecovery;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private ExecutorService buildScheduleJobsExecutor;

    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;

        asyncBuildScheduleJobs();

        asyncScheduleJobs();
        log.info("JobScheduler started successfully.");
    }

    /**
     * 已知限制（调度语义见 docs/spec/2026-08-03-admin-ha-spec.md 决策 #8）：
     * 1. 定时扫描数据库存在最长约 1s 的调度延迟，秒级精度任务可能错过火点：普通任务不补偿，单次任务仅在 HA 接管时补触发；
     * 2. 数据以游标分批加载进内存，任务量极大时存在内存压力，需评估更稳定的方案。
     */
    private void asyncBuildScheduleJobs() {
        buildScheduleJobsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "job-scheduler-build");
            thread.setDaemon(true);
            return thread;
        });
        buildScheduleJobsExecutor.execute(() -> {
            while (running) {
                try {
                    long start = System.currentTimeMillis();
                    maybeReconcile();
                    long sleepTime = BUILD_SCAN_INTERVAL_MS - (System.currentTimeMillis() - start);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("job scheduler build error: ", e);
                }
            }
        });
    }

    void maybeReconcile() {
        if (leaderElector.isLeader()) {
            reconcileQueuedJobs();
        }
    }

    /**
     * 全量对账：投影游标查询（status=1 且 finished=0），只入队新增/变更任务，移除消失任务。
     */
    void reconcileQueuedJobs() {
        Set<Long> seen = new HashSet<>();
        long offset = 0;
        var jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (JobView job : jobs) {
                seen.add(job.id());
                if (singleRunTracker.contains(job.id())) {
                    continue;
                }
                queuedJobs.compute(job.id(), (id, queued) -> {
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
            offset = jobs.getLast().id();
            jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        }

        // 清扫：已从查询消失（禁用/删除/Finished）的 in-flight 标记与队列条目
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
                    scheduleWorkers[workerIndex(scheduleJob.job().id(), threads)]
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

    void handle(ScheduleJob scheduleJob) {
        try {
            if (!leaderElector.isLeader()) {
                return;
            }
            JobView job = scheduleJob.job();
            queuedJobs.remove(job.id(), scheduleJob);
            if (isSingleRun(job)) {
                singleRunTracker.add(job.id());
            } else {
                requeue(job);
            }
            scheduleJobService.schedule(job.toJob());
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
        }
    }

    private void requeue(JobView job) {
        queuedJobs.compute(job.id(), (id, old) -> {
            if (old != null) {
                return old;
            }
            ScheduleJob next = ScheduleJob.of(job);
            schedulerEngine.add(next);
            return next;
        });
    }

    @Override
    public void onBecomeLeader() {
        if (scheduleProps.isHaEnabled()) {
            scheduleRunRecovery.recover();
        }
        schedulerEngine.start();
        reconcileQueuedJobs();
        log.info("JobScheduler became leader, queue rebuilt");
    }

    @Override
    public void onLoseLeadership() {
        queuedJobs.forEach((jobId, queued) -> schedulerEngine.remove(queued));
        queuedJobs.clear();
        singleRunTracker.clear();
        schedulerEngine.stop();
        log.info("JobScheduler lost leadership, local queue cleared");
    }

    private boolean isMetadataChanged(JobView queued, JobView current) {
        return !Objects.equals(queued.cron(), current.cron())
                || !Objects.equals(queued.executeParam(), current.executeParam())
                || !Objects.equals(queued.strategy(), current.strategy())
                || !Objects.equals(queued.type(), current.type());
    }

    private boolean isSingleRun(JobView job) {
        return job.type() != null && job.type() == JobTypeEnum.SINGLE.getCode();
    }

    @Override
    public void stop() {
        this.running = false;
        schedulerEngine.stop();
        shutdownGracefully(buildScheduleJobsExecutor);
        shutdownGracefully(dispatchExecutor);
        if (scheduleWorkers != null) {
            for (ExecutorService worker : scheduleWorkers) {
                shutdownGracefully(worker);
            }
        }
        log.info("JobScheduler stopped.");
    }

    private void shutdownGracefully(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobViewTest,JobSchedulerTest -DfailIfNoTests=false`

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/dao/entity/JobView.java admin/src/main/java/com/wly/job/server/schedule/ScheduleJob.java admin/src/main/java/com/wly/job/server/dao/rep/JobRep.java admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/test/java/com/wly/job/server/dao/entity/JobViewTest.java admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "refactor: 调度队列改为 JobView 轻量投影并接入投影游标查询"
```

---

### Task 3: 引擎 clear()（主备切换清理 O(n)）

**Files:**
- Modify: `common/src/main/java/com/wly/job/common/timewheel/TimeWheel.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/engine/SchedulerEngine.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/engine/DelayQueueSchedulerEngine.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngine.java`
- Test: `common/src/test/java/com/wly/job/common/timewheel/TimeWheelTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/engine/DelayQueueSchedulerEngineTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngineTest.java`

**Interfaces:**
- Produces: `SchedulerEngine.clear(): void`
- Produces: `TimeWheel.clear(): void`

- [ ] **Step 1: Write the failing tests**

`common/src/test/java/com/wly/job/common/timewheel/TimeWheelTest.java` 增加用例：

```java
@Test
void clearEmptiesAllSlots() {
    StringWheel wheel = new StringWheel(1, 60);
    long expire = System.currentTimeMillis() + 10_000;
    wheel.add("job-1", expire);

    wheel.clear();

    assertNull(wheel.getAndRemove(expire));
}
```

`admin/src/test/java/com/wly/job/server/schedule/engine/DelayQueueSchedulerEngineTest.java`：

```java
package com.wly.job.server.schedule.engine;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.schedule.ScheduleJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayQueueSchedulerEngineTest {

    private static ScheduleJob job(long id) {
        return ScheduleJob.of(JobView.of(Job.builder().id(id).name("j" + id)
                .cron("0/5 * * * * ?").type(0).finished(0).build()));
    }

    @Test
    void clearRemovesAllEntries() {
        DelayQueueSchedulerEngine engine = new DelayQueueSchedulerEngine();
        engine.add(job(1L));
        engine.add(job(2L));

        assertFalse(engine.isEmpty());
        engine.clear();

        assertTrue(engine.isEmpty());
    }
}
```

`admin/src/test/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngineTest.java`：

```java
package com.wly.job.server.schedule.engine;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.schedule.ScheduleJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWheelSchedulerEngineTest {

    @Test
    void clearEmptiesReadyQueue() {
        TimeWheelSchedulerEngine engine = new TimeWheelSchedulerEngine();
        ScheduleJob base = ScheduleJob.of(JobView.of(Job.builder().id(1L).name("j")
                .cron("0/5 * * * * ?").type(0).finished(0).build()));
        engine.add(new ScheduleJob(base.job(), System.nanoTime() - 1_000_000L));

        engine.clear();

        assertTrue(engine.isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test -Dtest=TimeWheelTest,DelayQueueSchedulerEngineTest,TimeWheelSchedulerEngineTest -DfailIfNoTests=false`

Expected: 编译失败（`clear()` 不存在）。

- [ ] **Step 3: Implement clear()**

`common/src/main/java/com/wly/job/common/timewheel/TimeWheel.java` 增加：

```java
public void clear() {
    lock.lock();
    try {
        for (int i = 0; i < entries.size(); i++) {
            entries.set(i, null);
        }
    } finally {
        lock.unlock();
    }
}
```

`admin/src/main/java/com/wly/job/server/schedule/engine/SchedulerEngine.java` 接口增加 `void clear();`：

```java
public interface SchedulerEngine {
    void add(ScheduleJob scheduleJob);

    void addAll(Collection<ScheduleJob> scheduleJobs);

    ScheduleJob take() throws InterruptedException;

    boolean remove(ScheduleJob scheduleJob);

    boolean isEmpty();

    void clear();

    default void start() {
    }

    default void stop() {
    }
}
```

`DelayQueueSchedulerEngine.clear()`：

```java
@Override
public void clear() {
    queue.clear();
}
```

`TimeWheelSchedulerEngine.clear()`：

```java
@Override
public void clear() {
    readyQueue.clear();
    timeWheel.clear();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=TimeWheelTest,DelayQueueSchedulerEngineTest,TimeWheelSchedulerEngineTest -DfailIfNoTests=false`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/wly/job/common/timewheel/TimeWheel.java common/src/test/java/com/wly/job/common/timewheel/TimeWheelTest.java admin/src/main/java/com/wly/job/server/schedule/engine/SchedulerEngine.java admin/src/main/java/com/wly/job/server/schedule/engine/DelayQueueSchedulerEngine.java admin/src/main/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngine.java admin/src/test/java/com/wly/job/server/schedule/engine/DelayQueueSchedulerEngineTest.java admin/src/test/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngineTest.java
git commit -m "feat: SchedulerEngine 增加 clear()，主备切换清理降为 O(n)"
```

---

### Task 4: JobScheduler 变更源消费与竞态闭合

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`

**Consumes:**
- `JobChangeRep.listAfter(long, int): List<JobChange>`、`JobChangeRep.maxId(): long`、`JobChangeRep.deleteUpTo(long): int`（Task 1）
- `SchedulerEngine.clear()`（Task 3）

- [ ] **Step 1: Write the failing tests**

`admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java` 全文替换为：

```java
package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);
    private final ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);

    private JobScheduler scheduler() {
        when(leaderElector.isLeader()).thenReturn(true);
        when(changeRep.listAfter(anyLong(), anyInt())).thenReturn(List.of());
        when(jobRep.batchQueryJobViewsByCursor(anyLong(), anyInt())).thenReturn(List.of());
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery, changeRep);
    }

    private ScheduleProps props() {
        ScheduleProps props = new ScheduleProps();
        props.setDispatchThreads(1);
        return props;
    }

    private static Job job(long id, String name, int type, int finished) {
        return Job.builder().id(id).name(name).type(type).finished(finished).cron("0/5 * * * * ?").build();
    }

    private static JobChange change(long id, long jobId) {
        JobChange c = new JobChange();
        c.setId(id);
        c.setJobId(jobId);
        return c;
    }

    @Test
    void finishedSingleRunNeverReturnedByQueryAndSweepsInFlight() {
        tracker.add(1L);

        scheduler().reconcileQueuedJobs();

        verify(engine, never()).add(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void enabledGeneralJobIsQueuedOnce() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());

        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        scheduler.reconcileQueuedJobs();

        verify(engine).add(any());
    }

    @Test
    void inFlightSingleRunIsNotRequeuedByFullReconcile() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(3L, "once", 1, 0))), List.of());
        JobScheduler scheduler = scheduler();
        tracker.add(3L);

        scheduler.reconcileQueuedJobs();

        verify(engine, never()).add(any());
    }

    @Test
    void consumeChangeFeedAddsNewJobAndAdvancesWatermark() {
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));
        JobScheduler scheduler = scheduler();

        scheduler.consumeChangeFeed();
        scheduler.consumeChangeFeed();

        verify(engine).add(any());
        verify(changeRep).listAfter(11L, 500);
    }

    @Test
    void applyChangeSkipsAddWhileInFlight() {
        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 0));
        JobScheduler scheduler = scheduler();
        tracker.add(2L);

        scheduler.consumeChangeFeed();

        verify(engine, never()).add(any());
    }

    @Test
    void applyChangeRemovesQueuedEntryWhenFinished() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "once", 1, 0))), List.of());
        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();
        verify(engine).add(any());

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "once", 1, 1));

        scheduler.consumeChangeFeed();

        verify(engine).remove(any());
    }

    @Test
    void lateRequeueWhileAlreadyQueuedDoesNotDuplicate() {
        when(jobRep.batchQueryJobViewsByCursor(0, 1000)).thenReturn(
                List.of(JobView.of(job(2L, "every", 0, 0))), List.of());
        JobScheduler scheduler = scheduler();
        scheduler.reconcileQueuedJobs();

        when(changeRep.listAfter(0L, 500)).thenReturn(List.of(change(11L, 2L)));
        when(jobRep.getById(2L)).thenReturn(job(2L, "every", 0, 0));

        scheduler.consumeChangeFeed();

        verify(engine).add(any());
        verify(engine, never()).remove(any());
    }

    @Test
    void handleSyncFailureReleasesInFlightAndRecordsRequeue() {
        doThrow(new RuntimeException("no instance")).when(scheduleJobService).schedule(any());
        JobScheduler scheduler = scheduler();

        scheduler.handle(ScheduleJob.of(JobView.of(job(7L, "once", 1, 0))));

        assertFalse(tracker.contains(7L));
        verify(changeRep).record(7L, JobChangeTypeEnum.REQUEUE.getCode(), "system", null, "once");
    }

    @Test
    void fullReconcileRunsEvery60Scans() {
        JobScheduler scheduler = scheduler();
        for (int i = 0; i < 60; i++) {
            scheduler.maybeReconcile();
        }
        verify(jobRep, times(1)).batchQueryJobViewsByCursor(anyLong(), anyInt());
    }

    @Test
    void cleanupRunsEvery300Scans() {
        JobScheduler scheduler = scheduler();
        for (int i = 0; i < 300; i++) {
            scheduler.maybeReconcile();
        }
        verify(changeRep).deleteUpTo(anyLong());
    }

    @Test
    void workerIndexRoutesByJobId() {
        assertEquals(0, JobScheduler.workerIndex(2L, 2));
        assertEquals(1, JobScheduler.workerIndex(3L, 2));
        assertEquals(1, JobScheduler.workerIndex(-1L, 2));
        assertEquals(0, JobScheduler.workerIndex(null, 2));
    }

    @Test
    void maybeReconcileSkipsWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.maybeReconcile();

        verify(jobRep, never()).batchQueryJobViewsByCursor(anyLong(), anyInt());
        verify(changeRep, never()).listAfter(anyLong(), anyInt());
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.handle(ScheduleJob.of(JobView.of(job(1L, "once", 0, 0))));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void loseLeadershipClearsTrackerAndEngine() {
        tracker.add(1L);

        scheduler().onLoseLeadership();

        verify(engine).clear();
        verify(engine).stop();
        assertFalse(tracker.contains(1L));
    }

    @Test
    void becomeLeaderStartsEngineReconcilesAndSetsWatermark() {
        when(changeRep.maxId()).thenReturn(42L);

        scheduler().onBecomeLeader();

        verify(engine).start();
        verify(changeRep).maxId();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobSchedulerTest -DfailIfNoTests=false`

Expected: 失败（`consumeChangeFeed` / `applyChange` 不存在、构造参数不匹配、`clear()` 未调用等）。

- [ ] **Step 3: Implement final JobScheduler**

`admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java` 全文替换为：

```java
package com.wly.job.server.schedule;

import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.JobChange;
import com.wly.job.server.dao.entity.JobView;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.ha.LeadershipListener;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.service.ScheduleJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobScheduler implements SmartLifecycle, LeadershipListener {

    private static final long BUILD_SCAN_INTERVAL_MS = 1000L;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000L;

    private static final int CHANGE_FEED_BATCH_SIZE = 500;

    /** 每 60 次扫描（约 60s）执行一次全量兜底对账 */
    private static final int FULL_RECONCILE_EVERY_SCANS = 60;

    /** 每 300 次扫描（约 5min）清理已消费变更记录 */
    private static final int CHANGE_CLEANUP_EVERY_SCANS = 300;

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final SchedulerEngine schedulerEngine;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps scheduleProps;

    private final ScheduleLeaderElector leaderElector;

    private final ScheduleRunRecovery scheduleRunRecovery;

    private final JobChangeRep changeRep;

    private volatile boolean running = false;

    /**
     * 已投递到调度引擎的任务（jobId -> 队列中的实例），用于幂等入队与移除
     */
    private final ConcurrentMap<Long, ScheduleJob> queuedJobs = new ConcurrentHashMap<>();

    private long changeFeedWatermark = 0L;

    private int scansSinceFullReconcile = 0;

    private int scansSinceChangeCleanup = 0;

    private ExecutorService buildScheduleJobsExecutor;

    private ExecutorService dispatchExecutor;

    private ExecutorService[] scheduleWorkers;

    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;

        asyncBuildScheduleJobs();

        asyncScheduleJobs();
        log.info("JobScheduler started successfully.");
    }

    /**
     * 调度队列构建与对账（方案见 docs/spec/2026-08-04-scheduler-scalability-spec.md）：
     * 稳态每秒消费变更源，成本 ∝ 变更量；每 60s 执行一次投影全量兜底对账，自愈直改库与漏写。
     */
    private void asyncBuildScheduleJobs() {
        buildScheduleJobsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "job-scheduler-build");
            thread.setDaemon(true);
            return thread;
        });
        buildScheduleJobsExecutor.execute(() -> {
            while (running) {
                try {
                    long start = System.currentTimeMillis();
                    maybeReconcile();
                    long sleepTime = BUILD_SCAN_INTERVAL_MS - (System.currentTimeMillis() - start);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("job scheduler build error: ", e);
                }
            }
        });
    }

    void maybeReconcile() {
        if (!leaderElector.isLeader()) {
            return;
        }
        consumeChangeFeed();
        if (++scansSinceFullReconcile >= FULL_RECONCILE_EVERY_SCANS) {
            scansSinceFullReconcile = 0;
            reconcileQueuedJobs();
        }
        if (++scansSinceChangeCleanup >= CHANGE_CLEANUP_EVERY_SCANS) {
            scansSinceChangeCleanup = 0;
            changeRep.deleteUpTo(changeFeedWatermark);
        }
    }

    void consumeChangeFeed() {
        for (JobChange change : changeRep.listAfter(changeFeedWatermark, CHANGE_FEED_BATCH_SIZE)) {
            applyChange(change.getJobId());
            changeFeedWatermark = change.getId();
        }
    }

    void applyChange(Long jobId) {
        Job current = jobRep.getById(jobId);
        queuedJobs.compute(jobId, (id, queued) -> {
            if (singleRunTracker.contains(id)) {
                // 在途：队列必无该任务，跳过整条记录（防跨主迟到 REQUEUE 双发）
                return queued;
            }
            if (!isActive(current)) {
                if (queued != null) {
                    schedulerEngine.remove(queued);
                }
                return null;
            }
            JobView view = JobView.of(current);
            if (queued == null) {
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                return scheduleJob;
            }
            if (isMetadataChanged(queued.job(), view)) {
                schedulerEngine.remove(queued);
                ScheduleJob scheduleJob = ScheduleJob.of(view);
                schedulerEngine.add(scheduleJob);
                return scheduleJob;
            }
            return queued;
        });
    }

    private boolean isActive(Job job) {
        return job != null
                && Objects.equals(job.getStatus(), Job.ENABLE)
                && !(Objects.equals(job.getType(), JobTypeEnum.SINGLE.getCode())
                        && Objects.equals(job.getFinished(), 1));
    }

    /**
     * 全量对账：投影游标查询（status=1 且 finished=0），只入队新增/变更任务，移除消失任务。
     */
    void reconcileQueuedJobs() {
        Set<Long> seen = new HashSet<>();
        long offset = 0;
        var jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (JobView job : jobs) {
                seen.add(job.id());
                if (singleRunTracker.contains(job.id())) {
                    continue;
                }
                queuedJobs.compute(job.id(), (id, queued) -> {
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
            offset = jobs.getLast().id();
            jobs = jobRep.batchQueryJobViewsByCursor(offset, 1000);
        }

        // 清扫：已从查询消失（禁用/删除/Finished）的 in-flight 标记与队列条目
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
                    scheduleWorkers[workerIndex(scheduleJob.job().id(), threads)]
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

    void handle(ScheduleJob scheduleJob) {
        JobView job = scheduleJob.job();
        try {
            if (!leaderElector.isLeader()) {
                return;
            }
            if (isSingleRun(job)) {
                // 先置 in-flight 再摘除条目，闭合消费线程插入的竞态窗口
                singleRunTracker.add(job.id());
                queuedJobs.remove(job.id(), scheduleJob);
            } else {
                queuedJobs.remove(job.id(), scheduleJob);
                requeue(job);
            }
            scheduleJobService.schedule(job.toJob());
        } catch (Exception e) {
            log.error("schedule job execute error: ", e);
            if (isSingleRun(job)) {
                singleRunTracker.remove(job.id());
                changeRep.record(job.id(), JobChangeTypeEnum.REQUEUE.getCode(),
                        "system", null, job.name());
            }
        }
    }

    private void requeue(JobView job) {
        queuedJobs.compute(job.id(), (id, old) -> {
            if (old != null) {
                return old;
            }
            ScheduleJob next = ScheduleJob.of(job);
            schedulerEngine.add(next);
            return next;
        });
    }

    @Override
    public void onBecomeLeader() {
        if (scheduleProps.isHaEnabled()) {
            scheduleRunRecovery.recover();
        }
        schedulerEngine.clear();
        schedulerEngine.start();
        reconcileQueuedJobs();
        changeFeedWatermark = changeRep.maxId();
        log.info("JobScheduler became leader, queue rebuilt");
    }

    @Override
    public void onLoseLeadership() {
        queuedJobs.clear();
        singleRunTracker.clear();
        schedulerEngine.clear();
        schedulerEngine.stop();
        changeFeedWatermark = 0L;
        log.info("JobScheduler lost leadership, local queue cleared");
    }

    private boolean isMetadataChanged(JobView queued, JobView current) {
        return !Objects.equals(queued.cron(), current.cron())
                || !Objects.equals(queued.executeParam(), current.executeParam())
                || !Objects.equals(queued.strategy(), current.strategy())
                || !Objects.equals(queued.type(), current.type());
    }

    private boolean isSingleRun(JobView job) {
        return job.type() != null && job.type() == JobTypeEnum.SINGLE.getCode();
    }

    @Override
    public void stop() {
        this.running = false;
        schedulerEngine.stop();
        shutdownGracefully(buildScheduleJobsExecutor);
        shutdownGracefully(dispatchExecutor);
        if (scheduleWorkers != null) {
            for (ExecutorService worker : scheduleWorkers) {
                shutdownGracefully(worker);
            }
        }
        log.info("JobScheduler stopped.");
    }

    private void shutdownGracefully(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
```

注意：本任务同时清除了原 FIXME 注释（方案已固化，见 Spec）。

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=JobSchedulerTest -DfailIfNoTests=false`

Expected: PASS（16 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "feat: JobScheduler 接入变更源增量对账（水印/幂等 apply/in-flight 守卫），清理 FIXME"
```

---

### Task 5: 写路径埋点与删除接口（change_type 1-6 全量落地）

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/service/ScheduleJobService.java`
- Modify: `admin/src/main/java/com/wly/job/server/service/JobService.java`
- Modify: `admin/src/main/java/com/wly/job/server/controller/JobController.java`
- Modify: `admin/src/main/java/com/wly/job/server/client/callback/ScheduleRecCallback.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/ScheduleRunRecovery.java`
- Test: `admin/src/test/java/com/wly/job/server/service/ScheduleJobServiceTest.java`（新建）
- Test: `admin/src/test/java/com/wly/job/server/service/JobServiceTest.java`
- Test: `admin/src/test/java/com/wly/job/server/client/callback/ScheduleRecCallbackTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/ScheduleRunRecoveryTest.java`

**Consumes:**
- `JobChangeRep.record(...)`、`JobChangeTypeEnum`（Task 1）
- `JobScheduler` 变更源消费（Task 4）

- [ ] **Step 1: Write the failing tests**

`admin/src/test/java/com/wly/job/server/service/ScheduleJobServiceTest.java`（新建）：

```java
package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleJobServiceTest {

    private final Registry registry = mock(Registry.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final ScheduleJobService service =
            new ScheduleJobService(registry, jobRep, recQueue, scheduleService, changeRep);

    private static JobInfo info() {
        return JobInfo.builder()
                .jobname("j").group("g").cron("0/5 * * * * ?")
                .instance(JobInstance.builder().build())
                .build();
    }

    @Test
    void registerJobRecordsRegisterChange() {
        when(jobRep.save(any(Job.class))).thenReturn(true);

        service.registerJob(info());

        verify(changeRep).record(any(), eq(JobChangeTypeEnum.REGISTER.getCode()),
                eq("system"), isNull(), eq("j"));
    }

    @Test
    void duplicateRegisterDoesNotRecordChange() {
        when(jobRep.save(any(Job.class))).thenThrow(new DuplicateKeyException("dup"));

        service.registerJob(info());

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }
}
```

`admin/src/test/java/com/wly/job/server/service/JobServiceTest.java` 全文替换为：

```java
package com.wly.job.server.service;

import com.wly.job.common.exception.ScheduleException;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.pojo.req.EditJobReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final JobService jobService = new JobService(jobRep, scheduleJobService, changeRep);

    private static Job existing(long id, int type) {
        return Job.builder().id(id).name("j").type(type).finished(0).status(1).build();
    }

    @Test
    void editWithInvalidCronRejected() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).cron("bad-cron").build();

        assertThrows(ScheduleException.class, () -> jobService.edit(req));
    }

    @Test
    void editWithValidCronUpdatesAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).cron("0/5 * * * * ?").build();

        assertDoesNotThrow(() -> jobService.edit(req));

        verify(jobRep).updateById(any(Job.class));
        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.EDIT.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void editWithoutCronSkipsValidation() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));
        EditJobReq req = EditJobReq.builder().id(1L).description("only desc").build();

        assertDoesNotThrow(() -> jobService.edit(req));

        verify(jobRep).updateById(any(Job.class));
    }

    @Test
    void editConvertingSingleRunToGeneralResetsFinished() {
        when(jobRep.getById(1L)).thenReturn(
                Job.builder().id(1L).name("j").type(1).finished(1).status(1).build());

        jobService.edit(EditJobReq.builder().id(1L).type(0).build());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRep).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFinished());
    }

    @Test
    void editNotFoundThrows() {
        when(jobRep.getById(1L)).thenReturn(null);

        assertThrows(ScheduleException.class, () -> jobService.edit(EditJobReq.builder().id(1L).build()));
    }

    @Test
    void switchStatusFlipsAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));

        jobService.switchStatus(1L);

        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.SWITCH.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void deleteRemovesAndRecords() {
        when(jobRep.getById(1L)).thenReturn(existing(1L, 0));

        jobService.delete(1L);

        verify(jobRep).removeById(1L);
        verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.DELETE.getCode()), any(), isNull(), eq("j"));
    }

    @Test
    void deleteNotFoundThrows() {
        when(jobRep.getById(1L)).thenReturn(null);

        assertThrows(ScheduleException.class, () -> jobService.delete(1L));
    }
}
```

`admin/src/test/java/com/wly/job/server/client/callback/ScheduleRecCallbackTest.java` 全文替换为：

```java
package com.wly.job.server.client.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleRecCallbackTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.wly.job.server.dao.entity.Job.class);
    }

    private final ScheduleRecQueue recQueue = mock(ScheduleRecQueue.class);
    private final JobRep jobRep = mock(JobRep.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final JobChangeRep changeRep = mock(JobChangeRep.class);
    private final ScheduleRecCallback callback = new ScheduleRecCallback(recQueue, jobRep, tracker, changeRep);

    private static ScheduleCallbackContext ctx(String requestId) {
        return new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId(requestId).jobname("once-7").build(), 7L, true);
    }

    @Test
    void onSuccessOfSingleRunMarksFinishedAndRecords() {
        tracker.add(7L);
        when(jobRep.update(isNull(), any())).thenReturn(true);

        callback.onSuccess(ctx("req-1"), "ok");

        assertFalse(tracker.contains(7L));
        verify(recQueue).markSuccess("req-1", "\"ok\"");
        verify(jobRep).update(isNull(), any());
        verify(changeRep).record(eq(7L), eq(JobChangeTypeEnum.FINISHED.getCode()),
                eq("system"), eq("req-1"), eq("once-7"));
    }

    @Test
    void onSuccessSkipsRecordWhenUpdateMissed() {
        when(jobRep.update(isNull(), any())).thenReturn(false);

        callback.onSuccess(ctx("req-1"), "ok");

        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void onFailureRemovesInFlightMarksFailAndRecordsRequeue() {
        tracker.add(7L);

        callback.onFailure(ctx("req-1"), new RuntimeException("boom"));

        assertFalse(tracker.contains(7L));
        verify(recQueue).markFail("req-1", "boom");
        verify(changeRep).record(eq(7L), eq(JobChangeTypeEnum.REQUEUE.getCode()),
                eq("system"), eq("req-1"), eq("once-7"));
    }

    @Test
    void onSuccessOfGeneralJobDoesNotTouchJob() {
        ScheduleCallbackContext general = new ScheduleCallbackContext(
                ScheduleJobRequest.builder().requestId("req-1").build(), 7L, false);

        callback.onSuccess(general, "ok");

        verify(jobRep, never()).update(any(), any());
        verify(changeRep, never()).record(any(), any(), any(), any(), any());
    }
}
```

`admin/src/test/java/com/wly/job/server/schedule/ScheduleRunRecoveryTest.java` 修改两处：

字段区增加 `private final JobChangeRep changeRep = mock(JobChangeRep.class);`（import `com.wly.job.server.dao.rep.JobChangeRep`），`recovery()` 构造改为：

```java
return new ScheduleRunRecovery(jobRep, recRep, scheduleJobService, tracker, props, leaderElector, changeRep);
```

`sweepReleasesStaleInFlightAndMarksFail` 增加断言：

```java
verify(changeRep).record(eq(1L), eq(JobChangeTypeEnum.REQUEUE.getCode()),
        eq("system"), isNull(), isNull());
```

（import `com.wly.job.server.enumeration.JobChangeTypeEnum`，以及 `eq` / `isNull` 静态导入。）

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=ScheduleJobServiceTest,JobServiceTest,ScheduleRecCallbackTest,ScheduleRunRecoveryTest -DfailIfNoTests=false`

Expected: 编译失败（`JobService` / `ScheduleJobService` 构造参数不匹配、`changeRep` 字段缺失等）。

- [ ] **Step 3: Implement write-path emission**

`admin/src/main/java/com/wly/job/server/service/ScheduleJobService.java` 全文替换为：

```java
package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.ScheduleService;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduleJobService {

    private final Registry registry;

    private final JobRep jobRep;

    private final ScheduleRecQueue recQueue;

    private final ScheduleService scheduleService;

    private final JobChangeRep changeRep;

    @Transactional
    public void registerJob(JobInfo jobInfo) {
        if (jobInfo == null || jobInfo.getInstance() == null) {
            throw new ScheduleException("JobInfo and instance must not be null");
        }
        if (!StringUtils.hasText(jobInfo.getJobname()) || !StringUtils.hasText(jobInfo.getCron())) {
            throw new ScheduleException("Job name and cron must not be blank");
        }
        CronUtils.checkCronExpression(jobInfo.getCron());

        registry.register(jobInfo.getInstance());
        Job job = JobBeanConverter.convert(jobInfo).init();
        job.setCreator("system");
        job.setUpdater("system");
        try {
            jobRep.save(job);
        } catch (DuplicateKeyException exception) {
            log.warn("job already exists, register fail, job: {}", job.getGroupName() + ":" + job.getName());
            return;
        }
        changeRep.record(job.getId(), JobChangeTypeEnum.REGISTER.getCode(),
                "system", null, job.getName());
    }

    public void registerInstance(JobInstance instance) {
        registry.register(instance);
    }

    public void schedule(Job job) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        ScheduleRec scheduleRec = ScheduleRec.builder()
                .jobId(job.getId())
                .requestId(requestId)
                .executeParam(job.getExecuteParam())
                .scheduleTime(new Date())
                .status(ScheduleRec.RUNNING)
                .operator(UserSessionContext.getUserName())
                .build();
        recQueue.save(scheduleRec);
        try {
            scheduleService.schedule(requestId, job);
        } catch (Exception e) {
            recQueue.markFail(requestId, e.getMessage());
            throw e;
        }
    }
}
```

`admin/src/main/java/com/wly/job/server/service/JobService.java` 全文替换为：

```java
package com.wly.job.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRep jobRep;

    private final ScheduleJobService scheduleJobService;

    private final JobChangeRep changeRep;

    @Transactional
    public void edit(EditJobReq req) {
        if (StringUtils.hasText(req.getCron())) {
            CronUtils.checkCronExpression(req.getCron());
        }
        Job current = jobRep.getById(req.getId());
        if (current == null) {
            throw new ScheduleException("任务不存在");
        }
        Job update = JobBeanConverter.convert(req);
        if (req.getType() != null
                && req.getType() == JobTypeEnum.GENERAL.getCode()
                && Objects.equals(current.getType(), JobTypeEnum.SINGLE.getCode())) {
            update.setFinished(0);
        }
        jobRep.updateById(update);
        changeRep.record(update.getId(), JobChangeTypeEnum.EDIT.getCode(),
                UserSessionContext.getUserName(), null, current.getName());
    }

    @Transactional
    public void switchStatus(Long id) {
        Job job = jobRep.getById(id);
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }
        job.setStatus(Objects.equals(job.getStatus(), Job.ENABLE) ? Job.UNABLE : Job.ENABLE);
        jobRep.updateById(job);
        changeRep.record(job.getId(), JobChangeTypeEnum.SWITCH.getCode(),
                UserSessionContext.getUserName(), null, job.getName());
    }

    @Transactional
    public void delete(Long id) {
        Job job = jobRep.getById(id);
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }
        changeRep.record(job.getId(), JobChangeTypeEnum.DELETE.getCode(),
                UserSessionContext.getUserName(), null, job.getName());
        jobRep.removeById(id);
    }

    public PageResp<JobResp> page(PageReq<QueryJobReq> pageReq) {
        LambdaQueryWrapper<Job> wrapper = Wrappers.<Job>lambdaQuery().orderByDesc(Job::getId);
        if (pageReq.getQuery() != null) {
            wrapper.likeRight(StringUtils.hasText(pageReq.getQuery().getGroupName()), Job::getGroupName, pageReq.getQuery().getGroupName())
                    .likeRight(StringUtils.hasText(pageReq.getQuery().getJobname()), Job::getName, pageReq.getQuery().getJobname());
        }
        Page<Job> page = jobRep.page(new Page<>(pageReq.getPageNum(), pageReq.getPageSize()), wrapper);
        return PageResp.of(page.convert(JobBeanConverter::convert).getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
    }

    public void exec(ExecJobReq req) {
        Job job = jobRep.getById(req.getJobId());
        if (job == null) {
            throw new ScheduleException("任务不存在");
        }

        if (!job.isEnable()) {
            throw new ScheduleException("任务已禁用");
        }

        if (StringUtils.hasText(req.getExecuteParam())) {
            job.setExecuteParam(req.getExecuteParam());
        }

        scheduleJobService.schedule(job);
    }
}
```

`admin/src/main/java/com/wly/job/server/controller/JobController.java` 全文替换为：

```java
package com.wly.job.server.controller;

import com.wly.job.common.bean.PageReq;
import com.wly.job.common.bean.PageResp;
import com.wly.job.common.bean.Result;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.req.ExecJobReq;
import com.wly.job.server.pojo.req.QueryJobReq;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 任务管理
 */
@RestController
@RequestMapping("/admin/job")
@RequiredArgsConstructor
public class JobController {
    private final JobService jobService;

    private final JobRep jobRep;

    @PostMapping("/page")
    public Result<PageResp<JobResp>> page(@RequestBody PageReq<QueryJobReq> pageReq) {
        return Result.success(jobService.page(pageReq));
    }

    @GetMapping("/detail")
    public Result<JobResp> detail(@RequestParam("id") Long id) {
        Job job = jobRep.getById(id);
        if (job == null) {
            return Result.fail("任务不存在");
        }
        return Result.success(JobBeanConverter.convert(job));
    }

    @PostMapping("/edit")
    public Result<Void> edit(@RequestBody EditJobReq req) {
        jobService.edit(req);
        return Result.success();
    }

    @PostMapping("/switch")
    public Result<Void> switchStatus(@RequestParam("id") Long id) {
        jobService.switchStatus(id);
        return Result.success();
    }

    @PostMapping("/exec")
    public Result<Void> exec(@RequestBody ExecJobReq req) {
        jobService.exec(req);
        return Result.success();
    }

    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam("id") Long id) {
        jobService.delete(id);
        return Result.success();
    }
}
```

`admin/src/main/java/com/wly/job/server/client/callback/ScheduleRecCallback.java` 全文替换为：

```java
package com.wly.job.server.client.callback;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.enumeration.JobChangeTypeEnum;
import com.wly.job.server.schedule.ScheduleRecQueue;
import com.wly.job.server.schedule.SingleRunTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置回调：执行结果回写 ScheduleRec；单次任务成功置 Finished、失败移出 in-flight 并写失败重试变更记录。
 */
@Component
@RequiredArgsConstructor
@Order(0)
public class ScheduleRecCallback implements ScheduleCallback {

    private final ScheduleRecQueue recQueue;

    private final JobRep jobRep;

    private final SingleRunTracker singleRunTracker;

    private final JobChangeRep changeRep;

    @Transactional
    @Override
    public void onSuccess(ScheduleCallbackContext context, Object result) {
        // 先置 Finished 再移除 in-flight：若先移除，对账线程可能在窗口内把任务重新入队造成重复执行
        if (context.singleRun() && context.jobId() != null) {
            boolean updated = jobRep.update(null, Wrappers.<Job>lambdaUpdate()
                    .eq(Job::getId, context.jobId())
                    .eq(Job::getType, JobTypeEnum.SINGLE.getCode())
                    .eq(Job::getFinished, 0)
                    .set(Job::getFinished, 1));
            if (updated) {
                changeRep.record(context.jobId(), JobChangeTypeEnum.FINISHED.getCode(),
                        "system", context.request().getRequestId(), context.request().getJobname());
            }
        }
        singleRunTracker.remove(context.jobId());
        recQueue.markSuccess(context.request().getRequestId(), JSON.toJSONString(result));
    }

    @Override
    public void onFailure(ScheduleCallbackContext context, Throwable cause) {
        singleRunTracker.remove(context.jobId());
        if (context.singleRun() && context.jobId() != null) {
            changeRep.record(context.jobId(), JobChangeTypeEnum.REQUEUE.getCode(),
                    "system", context.request().getRequestId(), context.request().getJobname());
        }
        recQueue.markFail(context.request().getRequestId(), cause == null ? null : cause.getMessage());
    }
}
```

`admin/src/main/java/com/wly/job/server/schedule/ScheduleRunRecovery.java` 修改：

字段区增加（import `com.wly.job.server.dao.rep.JobChangeRep` 与 `com.wly.job.server.enumeration.JobChangeTypeEnum`）：

```java
private final JobChangeRep changeRep;
```

`releaseStaleInFlight` 的释放分支改为：

```java
staleRecs.stream().map(ScheduleRec::getJobId).distinct()
        .filter(jobId -> !freshJobIds.contains(jobId))
        .forEach(jobId -> {
            singleRunTracker.remove(jobId);
            changeRep.record(jobId, JobChangeTypeEnum.REQUEUE.getCode(), "system", null, null);
            log.warn("Stale in-flight released, jobId: {}", jobId);
        });
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd -pl admin -am test -Dtest=ScheduleJobServiceTest,JobServiceTest,ScheduleRecCallbackTest,ScheduleRunRecoveryTest -DfailIfNoTests=false`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/service/ScheduleJobService.java admin/src/main/java/com/wly/job/server/service/JobService.java admin/src/main/java/com/wly/job/server/controller/JobController.java admin/src/main/java/com/wly/job/server/client/callback/ScheduleRecCallback.java admin/src/main/java/com/wly/job/server/schedule/ScheduleRunRecovery.java admin/src/test/java/com/wly/job/server/service/ScheduleJobServiceTest.java admin/src/test/java/com/wly/job/server/service/JobServiceTest.java admin/src/test/java/com/wly/job/server/client/callback/ScheduleRecCallbackTest.java admin/src/test/java/com/wly/job/server/schedule/ScheduleRunRecoveryTest.java
git commit -m "feat: 作业写路径埋点与删除接口（change_type 1-6 全量落地）"
```

---

### Task 6: 全量验证与收尾

**Files:** 无新增；如有测试失败则修复对应实现。

- [ ] **Step 1: 全量测试**

Run: `$env:JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10'; .\mvnw.cmd test`

Expected: BUILD SUCCESS，全部测试通过。

- [ ] **Step 2: 检查工作区状态**

Run: `git status --short`

Expected: 只剩 `build-full.log` 未跟踪（如无修复提交）；`JobScheduler.java` 的 FIXME 已在 Task 4 清理。

- [ ] **Step 3: 如有修复则提交**

如有修复，按内容提交（如 `fix: 全量测试失败修复`）；无修复则跳过本步。

---

## Self-Review

**Spec 覆盖：**

- 决策 #1 目标规模：架构决策，代码不直接体现（投影查询 + 增量消费为其支撑）。
- 决策 #2 时效 ≤1s：Task 4 `maybeReconcile` 每秒消费变更源。
- 决策 #3 变更源 + 60s 兜底：Task 1/4（`FULL_RECONCILE_EVERY_SCANS=60`）。
- 决策 #4 表结构：Task 1（实体/枚举），DDL 已提交。
- 决策 #5 消费模型：Task 4（水印/回查 diff/清理 `CHANGE_CLEANUP_EVERY_SCANS=300`）。
- 决策 #6 埋点清单：Task 5（六个 change_type 全部落地）。
- 决策 #7 队列语义：Task 4 `handle`（先 in-flight 再摘除）+ 同步异常 REQUEUE；Task 5 `onFailure`/`releaseStaleInFlight` 写 REQUEUE。
- 决策 #8 轻量投影：Task 2（`JobView` + 投影查询）。
- 决策 #9 投影查询 `status=1 AND finished=0`：Task 2 `batchQueryJobViewsByCursor`。
- 决策 #10 Finished 不变量：Task 5（edit 重置 + 回调守卫），迁移 SQL 已提交。
- 决策 #11 引擎 clear()：Task 3；Task 4 切换时使用。
- 决策 #12 in-flight 跳过整体 apply：Task 4 `applyChange`。
- 删除接口：Task 5（`JobService.delete` + `/admin/job/delete` + change_type=5）。

**占位符扫描：** 无 TBD/TODO/“相似于 Task N”式占位；每个代码步骤均给出完整代码。

**类型一致性：** `JobView.of/toJob`、`ScheduleJob.of(JobView)`、`JobChangeRep.record(Long,Integer,String,String,String)`、`JobScheduler` 构造参数（8 个，含 `JobChangeRep`）在 Task 1/2/4/5 的测试与实现中一致；`ScheduleRecCallback` 构造参数（4 个）在测试与实现中一致；`ScheduleRunRecovery` 构造参数（7 个）一致。
