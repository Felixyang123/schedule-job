# Phase A: Admin 单活 HA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Admin 引入单活 HA：`LeaderElection` 选主抽象（DB 租约默认 / Redis 可选）、`JobScheduler` 调度权门控与主备切换、接管恢复（陈旧 RUNNING 清理 + 单次任务补触发）。

**Architecture:** 新增单行锁表 `schedule_lock`；`ScheduleLeaderElector` 后台线程按租约 CAS 抢锁/续约并刷新 `isLeader` 标志；`JobScheduler` 只在 `isLeader` 时对账与派发，失去主时清空本地队列与 in-flight；成为主时先执行接管恢复，再全量对账。HA 关闭时 `AlwaysLeaderElection` 恒为主，行为与现状完全一致。

**Tech Stack:** Java 21、Spring Boot 3.5.6、MyBatis-Plus 3.5.7、spring-data-redis（既有依赖）、JUnit 5 + Mockito。

## Global Constraints

- JDK 21，构建命令：`$env:JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn test`
- 不引入新运行时依赖（Redis 选举复用既有 `spring-boot-starter-data-redis`）。
- 业务异常派生自 `ScheduleException`；Netty I/O 线程禁止 DB/网络 I/O（本阶段不涉及 Netty 线程）。
- `schedule.ha.enabled=false` 时行为与现状完全一致（向后兼容）。
- 每次 commit 前必须通过 `mvn -q -pl admin -am test` 验证（A0/A6 用全量 `mvn test`）。
- 提交前基线：Task A0 先把当前工作区所有未提交改动提交为基线 commit（执行前需用户确认）。

---

### Task A0: 提交基线（HA 决策文档 + 锁表脚本）

**Files:**
- 无新代码文件。提交现有未提交改动：`CONTEXT.md`、`docs/adr/0002-*`、`docs/adr/0004-*`、`docs/spec/2026-08-03-admin-ha-spec.md`、`docs/sql/schema.sql`、两份 plan 文档。

**Interfaces:**
- Consumes: 当前工作区所有未提交改动。
- Produces: 干净基线，后续任务可独立 commit。

- [ ] **Step 1: 确认基线范围**

Run: `git status --short`
Expected: 列出 docs/CONTEXT.md/schema.sql 等已修改与未跟踪文件。

- [ ] **Step 2: 提交前与用户确认"当前在途改动可以一起提交"**

- [ ] **Step 3: 提交基线**

```bash
git add -A
git commit -m "docs: Admin 单活 HA 决策固化（ADR-0004/Spec/Plan）与 schedule_lock 建表脚本"
```

- [ ] **Step 4: 验证基线**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

---

### Task A1: CronUtils.getPreviousExecution + zone()

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/utils/CronUtils.java`
- Modify: `admin/src/test/java/com/wly/job/server/utils/CronUtilsTest.java`

**Interfaces:**
- Consumes: 现有 `CronUtils`（Spring `CronExpression`）。
- Produces: `CronUtils.getPreviousExecution(String, LocalDateTime) -> LocalDateTime`（无则 null）；`CronUtils.zone() -> ZoneId`（Task A5 使用）。

- [ ] **Step 1: 追加失败测试**

`CronUtilsTest.java` 追加（文件末尾、最后一个 `}` 前）：

```java
    @Test
    void previousExecutionDaily() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 3, 10, 0, 30);
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", base);
        assertEquals(LocalDateTime.of(2026, 8, 3, 2, 0, 0), previous);
    }

    @Test
    void previousExecutionWhenBaseIsExactlyOnOccurrence() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 3, 2, 0, 0);
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", base);
        assertEquals(LocalDateTime.of(2026, 8, 2, 2, 0, 0), previous);
    }

    @Test
    void previousExecutionSparseFeb29() {
        LocalDateTime base = LocalDateTime.of(2027, 6, 1, 0, 0, 0);
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 0 29 2 ?", base);
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0, 0), previous);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=CronUtilsTest`
Expected: 编译失败（`getPreviousExecution` 不存在）。

- [ ] **Step 3: 实现**

`CronUtils.java` 在 `getNextExecutions` 后追加：

```java
    /**
     * 计算 baseTime 之前最近一次执行时间；不存在时返回 null。
     * 二分查找：predicate = "该时刻的下一次执行早于 baseTime"，找到最后一个满足的时刻。
     */
    public static LocalDateTime getPreviousExecution(String cron, LocalDateTime baseTime) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime lo = baseTime.minusYears(8).withNano(0);
            LocalDateTime hi = baseTime.minusSeconds(1).withNano(0);
            LocalDateTime first = expression.next(lo);
            if (first == null || !first.isBefore(baseTime)) {
                return null;
            }
            while (lo.isBefore(hi)) {
                long seconds = java.time.Duration.between(lo, hi).getSeconds();
                LocalDateTime mid = lo.plusSeconds(seconds / 2);
                LocalDateTime next = expression.next(mid);
                if (next != null && next.isBefore(baseTime)) {
                    lo = mid.plusSeconds(1);
                } else {
                    hi = mid;
                }
            }
            return expression.next(lo);
        } catch (IllegalArgumentException e) {
            throw new ScheduleException("CronExpression parse fail: " + cron, e);
        }
    }

    /**
     * 调度中心默认时区（供跨类型时间比较使用）
     */
    public static ZoneId zone() {
        return ZONE;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=CronUtilsTest`
Expected: PASS（6 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/utils/CronUtils.java admin/src/test/java/com/wly/job/server/utils/CronUtilsTest.java
git commit -m "feat: CronUtils 增加上次执行时间计算（HA 单次任务补触发用）"
```

---

### Task A2: LeaderElection 抽象 + DB/Redis 实现 + 配置

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/ha/LeaderElection.java`
- Create: `admin/src/main/java/com/wly/job/server/ha/AlwaysLeaderElection.java`
- Create: `admin/src/main/java/com/wly/job/server/ha/DbLeaderElection.java`
- Create: `admin/src/main/java/com/wly/job/server/ha/RedisLeaderElection.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/mapper/ScheduleLockMapper.java`
- Modify: `admin/src/main/java/com/wly/job/server/config/ScheduleProps.java`
- Modify: `admin/src/main/java/com/wly/job/server/config/ScheduleConfiguration.java`
- Modify: `admin/src/main/resources/application-dev.yml`
- Modify: `admin/src/main/resources/application-prod.yml`
- Test: `admin/src/test/java/com/wly/job/server/ha/DbLeaderElectionTest.java`
- Test: `admin/src/test/java/com/wly/job/server/ha/RedisLeaderElectionTest.java`

**Interfaces:**
- Consumes: 现有 `ScheduleProps`、`@MapperScan("com.wly.job.server.dao.mapper")`、`NetworkUtils`、`StringRedisTemplate`。
- Produces: `LeaderElection.acquireOrRenew()/release()`；`ScheduleLockMapper.ensureLockRow()/acquireOrRenew(owner, leaseSeconds)/release(owner)`；`ScheduleProps.haEnabled/haElection/haLeaseSeconds/haRenewSeconds/haPollSeconds/haStaleSweepSeconds/haInstanceId`；`ScheduleConfiguration` 的 `leaderElection`（HA 开启）与 `alwaysLeaderElection`（HA 关闭）Bean。

- [ ] **Step 1: 写失败测试**

`DbLeaderElectionTest.java`：

```java
package com.wly.job.server.ha;

import com.wly.job.server.dao.mapper.ScheduleLockMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DbLeaderElectionTest {

    private final ScheduleLockMapper lockMapper = mock(ScheduleLockMapper.class);

    @Test
    void acquiresWhenRowUpdated() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(1);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        assertTrue(election.acquireOrRenew());
        verify(lockMapper).ensureLockRow();
    }

    @Test
    void staysStandbyWhenRowNotUpdated() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(0);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        assertFalse(election.acquireOrRenew());
    }

    @Test
    void ensureRowRunsOnlyOnce() {
        when(lockMapper.acquireOrRenew("node-1", 10)).thenReturn(1);
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        election.acquireOrRenew();
        election.acquireOrRenew();

        verify(lockMapper, times(1)).ensureLockRow();
    }

    @Test
    void releaseDelegatesToMapper() {
        DbLeaderElection election = new DbLeaderElection(lockMapper, "node-1", 10);

        election.release();

        verify(lockMapper).release("node-1");
    }
}
```

`RedisLeaderElectionTest.java`：

```java
package com.wly.job.server.ha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedisLeaderElectionTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisLeaderElection election;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        election = new RedisLeaderElection(redisTemplate, "node-1", 10);
    }

    @Test
    void acquiresWithSetIfAbsent() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(true);

        assertTrue(election.acquireOrRenew());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void renewsWhenStillOwner() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-1");

        assertTrue(election.acquireOrRenew());
        verify(redisTemplate).expire("schedule:ha:leader", 10, TimeUnit.SECONDS);
    }

    @Test
    void rejectedWhenOwnedByOther() {
        when(valueOps.setIfAbsent("schedule:ha:leader", "node-1", 10, TimeUnit.SECONDS))
                .thenReturn(false);
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-2");

        assertFalse(election.acquireOrRenew());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void releaseOnlyDeletesOwnedLock() {
        when(valueOps.get("schedule:ha:leader")).thenReturn("node-1");
        election.release();
        verify(redisTemplate).delete("schedule:ha:leader");

        when(valueOps.get("schedule:ha:leader")).thenReturn("node-2");
        election.release();
        verify(redisTemplate, times(1)).delete("schedule:ha:leader");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=DbLeaderElectionTest,RedisLeaderElectionTest`
Expected: 编译失败（`LeaderElection` 等类不存在）。

- [ ] **Step 3: 实现**

`LeaderElection.java`：

```java
package com.wly.job.server.ha;

/**
 * Admin 选主抽象（ADR-0004）。
 * 实现必须保证 acquireOrRenew() 的原子性：同一时刻至多一个节点返回 true。
 */
public interface LeaderElection {

    /**
     * 原子抢锁或续约；返回 true 表示本次调用后当前节点持有调度权。
     */
    boolean acquireOrRenew();

    /**
     * 主动释放调度权（优雅停机/失去主时调用）。
     */
    void release();
}
```

`AlwaysLeaderElection.java`：

```java
package com.wly.job.server.ha;

/**
 * HA 关闭时的恒主实现：单节点部署行为与现状完全一致。
 */
public class AlwaysLeaderElection implements LeaderElection {

    @Override
    public boolean acquireOrRenew() {
        return true;
    }

    @Override
    public void release() {
        // 无锁可释放
    }
}
```

`ScheduleLockMapper.java`：

```java
package com.wly.job.server.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 单行选主锁（schedule_lock，id=1）CAS 操作，见 ADR-0004。
 */
public interface ScheduleLockMapper {

    @Insert("INSERT IGNORE INTO schedule_lock (id) VALUES (1)")
    int ensureLockRow();

    @Update("""
            UPDATE schedule_lock
            SET owner = #{owner}, expire_time = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)
            WHERE id = 1 AND (owner = #{owner} OR expire_time IS NULL OR expire_time < NOW())
            """)
    int acquireOrRenew(@Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE schedule_lock
            SET owner = NULL, expire_time = NULL
            WHERE id = 1 AND owner = #{owner}
            """)
    int release(@Param("owner") String owner);
}
```

`DbLeaderElection.java`：

```java
package com.wly.job.server.ha;

import com.wly.job.server.dao.mapper.ScheduleLockMapper;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DB 租约锁实现：单行 CAS（owner = 我 或 租约已过期）即可抢锁/续约，见 ADR-0004。
 */
@RequiredArgsConstructor
public class DbLeaderElection implements LeaderElection {

    private final ScheduleLockMapper lockMapper;

    private final String owner;

    private final long leaseSeconds;

    private final AtomicBoolean rowEnsured = new AtomicBoolean(false);

    @Override
    public boolean acquireOrRenew() {
        ensureLockRow();
        return lockMapper.acquireOrRenew(owner, leaseSeconds) == 1;
    }

    @Override
    public void release() {
        lockMapper.release(owner);
    }

    private void ensureLockRow() {
        if (rowEnsured.compareAndSet(false, true)) {
            lockMapper.ensureLockRow();
        }
    }
}
```

`RedisLeaderElection.java`：

```java
package com.wly.job.server.ha;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Redis 锁实现（schedule.ha.election=REDIS 时启用）：SET NX PX 抢锁 + 持有者续约，见 ADR-0004。
 */
@RequiredArgsConstructor
public class RedisLeaderElection implements LeaderElection {

    private static final String LOCK_KEY = "schedule:ha:leader";

    private final StringRedisTemplate redisTemplate;

    private final String owner;

    private final long leaseSeconds;

    @Override
    public boolean acquireOrRenew() {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, owner, leaseSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            return true;
        }
        if (owner.equals(redisTemplate.opsForValue().get(LOCK_KEY))) {
            redisTemplate.expire(LOCK_KEY, leaseSeconds, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    @Override
    public void release() {
        if (owner.equals(redisTemplate.opsForValue().get(LOCK_KEY))) {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
```

`ScheduleProps.java` 追加字段：

```java
    /**
     * HA 单活模式开关（多 Admin 部署时开启），默认 false
     */
    private boolean haEnabled = false;

    /**
     * 选主实现: DB（默认）/ REDIS
     */
    private String haElection = "DB";

    /**
     * 租约时长（秒），默认 10
     */
    private long haLeaseSeconds = 10;

    /**
     * 续约间隔（秒），默认 3
     */
    private long haRenewSeconds = 3;

    /**
     * 选主轮询间隔（秒），默认 1
     */
    private long haPollSeconds = 1;

    /**
     * 陈旧 RUNNING 记录清扫间隔（秒），默认 30
     */
    private long haStaleSweepSeconds = 30;

    /**
     * 节点唯一 ID（默认 host:port，由 ScheduleConfiguration 组装）
     */
    private String haInstanceId;
```

`ScheduleConfiguration.java` 追加两个 Bean：

```java
    @Bean
    @ConditionalOnProperty(prefix = "schedule.ha", name = "enabled", havingValue = "true")
    public LeaderElection leaderElection(ScheduleLockMapper lockMapper, StringRedisTemplate redisTemplate,
                                         ScheduleProps props, @Value("${server.port:8100}") int port) {
        String election = StringUtils.hasText(props.getHaElection())
                ? props.getHaElection().trim().toUpperCase()
                : "DB";
        String owner = StringUtils.hasText(props.getHaInstanceId())
                ? props.getHaInstanceId()
                : NetworkUtils.getServerIp() + ":" + port;
        if ("REDIS".equals(election)) {
            return new RedisLeaderElection(redisTemplate, owner, props.getHaLeaseSeconds());
        }
        return new DbLeaderElection(lockMapper, owner, props.getHaLeaseSeconds());
    }

    @Bean
    @ConditionalOnMissingBean(LeaderElection.class)
    public LeaderElection alwaysLeaderElection() {
        return new AlwaysLeaderElection();
    }
```

新增 import：`com.wly.job.common.utils.NetworkUtils`、`com.wly.job.server.dao.mapper.ScheduleLockMapper`、`com.wly.job.server.ha.AlwaysLeaderElection`、`com.wly.job.server.ha.DbLeaderElection`、`com.wly.job.server.ha.LeaderElection`、`com.wly.job.server.ha.RedisLeaderElection`、`org.springframework.beans.factory.annotation.Value`、`org.springframework.util.StringUtils`。

`application-dev.yml` 的 `schedule:` 段追加：

```yaml
  ha:
    enabled: false
    election: DB
    lease-seconds: 10
    renew-seconds: 3
    poll-seconds: 1
    stale-sweep-seconds: 30
```

`application-prod.yml` 的 `schedule:` 段追加：

```yaml
  ha:
    enabled: ${SCHEDULE_HA_ENABLED:false}
    election: ${SCHEDULE_HA_ELECTION:DB}
    lease-seconds: ${SCHEDULE_HA_LEASE_SECONDS:10}
    renew-seconds: ${SCHEDULE_HA_RENEW_SECONDS:3}
    poll-seconds: ${SCHEDULE_HA_POLL_SECONDS:1}
    stale-sweep-seconds: ${SCHEDULE_HA_STALE_SWEEP_SECONDS:30}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=DbLeaderElectionTest,RedisLeaderElectionTest`
Expected: PASS（8 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/ha admin/src/main/java/com/wly/job/server/dao/mapper/ScheduleLockMapper.java admin/src/main/java/com/wly/job/server/config/ScheduleProps.java admin/src/main/java/com/wly/job/server/config/ScheduleConfiguration.java admin/src/main/resources/application-dev.yml admin/src/main/resources/application-prod.yml admin/src/test/java/com/wly/job/server/ha
git commit -m "feat: LeaderElection 选主抽象（DB 租约默认/Redis 可选）与 schedule.ha 配置"
```

---

### Task A3: ScheduleLeaderElector + LeadershipListener

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/ha/LeadershipListener.java`
- Create: `admin/src/main/java/com/wly/job/server/ha/ScheduleLeaderElector.java`
- Test: `admin/src/test/java/com/wly/job/server/ha/ScheduleLeaderElectorTest.java`

**Interfaces:**
- Consumes: Task A2 的 `LeaderElection`、`ScheduleProps`。
- Produces: `LeadershipListener.onBecomeLeader()/onLoseLeadership()`；`ScheduleLeaderElector.isLeader()`、`addListener(LeadershipListener)`、`tick()`（package-private，供测试）；`getPhase() = Integer.MIN_VALUE`（先启动、后停止）；监听器通过 `ObjectProvider<LeadershipListener>` 延迟收集（避免与 JobScheduler 的构造注入环）。

- [ ] **Step 1: 写失败测试**

`ScheduleLeaderElectorTest.java`：

```java
package com.wly.job.server.ha;

import com.wly.job.server.config.ScheduleProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ScheduleLeaderElectorTest {

    private LeaderElection election;
    private ScheduleProps props;
    private LeadershipListener listener;
    private ScheduleLeaderElector elector;

    @BeforeEach
    void setUp() {
        election = mock(LeaderElection.class);
        listener = mock(LeadershipListener.class);
        props = new ScheduleProps();
        props.setHaEnabled(true);
        props.setHaPollSeconds(1);
        props.setHaRenewSeconds(3);
        props.setHaLeaseSeconds(10);
        elector = new ScheduleLeaderElector(election, props, new CopyOnWriteArrayList<>(List.of(listener)));
    }

    @Test
    void becomeLeaderWhenAcquireSucceeds() {
        when(election.acquireOrRenew()).thenReturn(true);

        elector.tick();

        assertTrue(elector.isLeader());
        verify(listener).onBecomeLeader();
    }

    @Test
    void staysStandbyWhenAcquireFails() {
        when(election.acquireOrRenew()).thenReturn(false);

        elector.tick();

        assertFalse(elector.isLeader());
        verify(listener, never()).onBecomeLeader();
    }

    @Test
    void stepDownWhenRenewFails() {
        props.setHaRenewSeconds(0);
        when(election.acquireOrRenew()).thenReturn(true, true, false);

        elector.tick(); // 成为主
        assertTrue(elector.isLeader());
        elector.tick(); // 续约成功
        assertTrue(elector.isLeader());
        elector.tick(); // 续约失败 -> 让位

        assertFalse(elector.isLeader());
        verify(listener).onLoseLeadership();
    }

    @Test
    void disabledModeIsAlwaysLeader() {
        props.setHaEnabled(false);

        elector.start();

        assertTrue(elector.isLeader());
        verify(listener).onBecomeLeader();
        elector.stop();
    }

    @Test
    void stopReleasesLockWhenLeader() {
        when(election.acquireOrRenew()).thenReturn(true);
        elector.tick();
        assertTrue(elector.isLeader());

        elector.stop();

        verify(election).release();
        assertFalse(elector.isLeader());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleLeaderElectorTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`LeadershipListener.java`：

```java
package com.wly.job.server.ha;

/**
 * 调度权变更监听器（JobScheduler 实现并通过 addListener 注册）。
 */
public interface LeadershipListener {

    void onBecomeLeader();

    void onLoseLeadership();
}
```

`ScheduleLeaderElector.java`：

```java
package com.wly.job.server.ha;

import com.wly.job.server.config.ScheduleProps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 选主循环：后台线程按 poll 间隔轮询，Leader 按 renew 间隔续约；
 * isLeader() 为 1s 级内存标志，派发前检查（ADR-0004 决策 #6）。
 * 监听器用 ObjectProvider 延迟收集：Spring 构造本组件时不会触发 JobScheduler 创建，
 * 避免构造注入环；start() 时再解析全部 LeadershipListener Bean。
 */
@Slf4j
@Component
public class ScheduleLeaderElector implements SmartLifecycle {

    private final LeaderElection leaderElection;

    private final ScheduleProps props;

    private final List<LeadershipListener> listeners = new CopyOnWriteArrayList<>();

    private ObjectProvider<LeadershipListener> listenerProvider;

    private volatile boolean leader = false;

    private volatile boolean running = false;

    private boolean listenersLoaded = false;

    private long lastRenewNanos;

    private Thread thread;

    @Autowired
    public ScheduleLeaderElector(LeaderElection leaderElection, ScheduleProps props,
                                 ObjectProvider<LeadershipListener> listenerProvider) {
        this.leaderElection = leaderElection;
        this.props = props;
        this.listenerProvider = listenerProvider;
    }

    /**
     * 测试用构造：直接提供监听器。
     */
    ScheduleLeaderElector(LeaderElection leaderElection, ScheduleProps props,
                          List<LeadershipListener> listeners) {
        this.leaderElection = leaderElection;
        this.props = props;
        this.listeners.addAll(listeners);
    }

    public boolean isLeader() {
        return leader;
    }

    public void addListener(LeadershipListener listener) {
        loadedListeners().add(listener);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        loadedListeners();
        if (!props.isHaEnabled()) {
            // 单节点/HA 关闭：恒为主，行为与现状一致
            setLeader(true);
            return;
        }
        thread = new Thread(this::loop, "schedule-leader-elector");
        thread.setDaemon(true);
        thread.start();
        log.info("ScheduleLeaderElector started, election: {}", props.getHaElection());
    }

    private void loop() {
        while (running) {
            try {
                tick();
                Thread.sleep(props.getHaPollSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Leader election loop error", e);
            }
        }
    }

    /**
     * 单次选举周期（package-private 供测试直接驱动）。
     */
    void tick() {
        long now = System.nanoTime();
        if (leader) {
            if (now - lastRenewNanos >= props.getHaRenewSeconds() * 1_000_000_000L) {
                if (!leaderElection.acquireOrRenew()) {
                    log.warn("Leader lease expired, step down");
                    setLeader(false);
                } else {
                    lastRenewNanos = now;
                }
            }
        } else if (leaderElection.acquireOrRenew()) {
            log.info("Become leader, instance: {}", props.getHaInstanceId());
            lastRenewNanos = now;
            setLeader(true);
        }
    }

    private List<LeadershipListener> loadedListeners() {
        if (!listenersLoaded) {
            listenersLoaded = true;
            if (listenerProvider != null) {
                listenerProvider.orderedStream().forEach(listeners::add);
            }
        }
        return listeners;
    }

    private void setLeader(boolean newLeader) {
        if (leader == newLeader) {
            return;
        }
        leader = newLeader;
        List<LeadershipListener> snapshot = loadedListeners();
        if (newLeader) {
            snapshot.forEach(LeadershipListener::onBecomeLeader);
        } else {
            snapshot.forEach(LeadershipListener::onLoseLeadership);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (leader) {
            leaderElection.release();
            setLeader(false);
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 最先启动、最后停止，保证 JobScheduler.start() 读取 isLeader 时已就绪
        return Integer.MIN_VALUE;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleLeaderElectorTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/ha/LeadershipListener.java admin/src/main/java/com/wly/job/server/ha/ScheduleLeaderElector.java admin/src/test/java/com/wly/job/server/ha/ScheduleLeaderElectorTest.java
git commit -m "feat: ScheduleLeaderElector 选主循环与调度权监听"
```

---

### Task A4: JobScheduler 调度权门控与主备切换

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/SingleRunTracker.java`
- Modify: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`

**Interfaces:**
- Consumes: Task A3 的 `ScheduleLeaderElector.isLeader()`、`LeadershipListener`。
- Produces: `JobScheduler.maybeReconcile()`（package-private）、`JobScheduler.handle(ScheduleJob)`（package-private）；`SingleRunTracker.clear()`；JobScheduler 实现 `LeadershipListener` 并通过 Spring 的 `ObjectProvider<LeadershipListener>` 自动注入到 elector（无需手动注册），`onBecomeLeader()` 启动引擎并全量对账，`onLoseLeadership()` 清空队列与 in-flight。

- [ ] **Step 1: 追加失败测试**

`JobSchedulerTest.java` 更新 `scheduler()` 并追加用例：

```java
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);

    private JobScheduler scheduler() {
        when(leaderElector.isLeader()).thenReturn(true);
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector);
    }
```

追加用例：

```java
    @Test
    void maybeReconcileSkipsWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);

        scheduler.maybeReconcile();

        verify(jobRep, never()).batchQueryJobsByCursor(anyLong(), anyInt());
    }

    @Test
    void handleSkipsDispatchWhenNotLeader() {
        JobScheduler scheduler = scheduler();
        when(leaderElector.isLeader()).thenReturn(false);
        Job job = Job.builder().id(1L).name("once").type(0).finished(0).cron("0/5 * * * * ?").build();

        scheduler.handle(ScheduleJob.of(job));

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void loseLeadershipClearsTrackerAndStopsEngine() {
        tracker.add(1L);

        scheduler().onLoseLeadership();

        verify(engine).stop();
        org.junit.jupiter.api.Assertions.assertFalse(tracker.contains(1L));
    }

    @Test
    void loseLeadershipRemovesQueuedEntries() {
        Job job = Job.builder().id(2L).name("every").type(0).finished(0).cron("0/5 * * * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(job), List.of());
        JobScheduler scheduler = scheduler();

        scheduler.reconcileQueuedJobs();
        scheduler.onLoseLeadership();

        verify(engine).remove(any());
    }

    @Test
    void becomeLeaderStartsEngineAndReconciles() {
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of());

        scheduler().onBecomeLeader();

        verify(engine).start();
    }
```

所需新 import：`com.wly.job.server.ha.ScheduleLeaderElector`、`org.mockito.ArgumentMatchers.anyInt`、`org.mockito.ArgumentMatchers.anyLong`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=JobSchedulerTest`
Expected: 编译失败（构造参数不匹配、方法不可见）。

- [ ] **Step 3: 实现**

`SingleRunTracker.java` 追加：

```java
    /**
     * 清空 in-flight（失去调度权时调用，交由新主按 At-Least-Once 重建）。
     */
    public void clear() {
        inFlight.clear();
    }
```

`JobScheduler.java`：

- 类声明改为 `public class JobScheduler implements SmartLifecycle, LeadershipListener`；
- 新增字段（监听器注册由 Spring 的 ObjectProvider 在 `ScheduleLeaderElector.start()` 时完成，无需手动注册）：

```java
    private final ScheduleLeaderElector leaderElector;
```

- `start()` 删除 `schedulerEngine.start();`（引擎启动移入 `onBecomeLeader`）；
- 构建循环中的 `reconcileQueuedJobs();` 替换为 `maybeReconcile();`；
- `handle` 改为 package-private 并在开头加门控：

```java
    void handle(ScheduleJob scheduleJob) {
        try {
            if (!leaderElector.isLeader()) {
                // 已失去调度权：丢弃本次触发，由新主重新对账（ADR-0004 决策 #6）
                return;
            }
            Job job = scheduleJob.job();
            ...
```

- 新增方法：

```java
    void maybeReconcile() {
        if (leaderElector.isLeader()) {
            reconcileQueuedJobs();
        }
    }

    @Override
    public void onBecomeLeader() {
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
```

新增 import：`com.wly.job.server.ha.LeadershipListener`、`com.wly.job.server.ha.ScheduleLeaderElector`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=JobSchedulerTest`
Expected: PASS（9 个用例）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/main/java/com/wly/job/server/schedule/SingleRunTracker.java admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "feat: JobScheduler 调度权门控与主备切换（清队列/in-flight、重建队列）"
```

---

### Task A5: ScheduleRunRecovery 常驻清扫与接管恢复

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/schedule/ScheduleRunRecovery.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
- Modify: `admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java`
- Test: `admin/src/test/java/com/wly/job/server/schedule/ScheduleRunRecoveryTest.java`

**Interfaces:**
- Consumes: Task A1 的 `CronUtils.getPreviousExecution/zone()`；Task A2 的 `ScheduleProps.haStaleSweepSeconds`；Task A3 的 `ScheduleLeaderElector.isLeader()`；Task A4 的 `JobScheduler.onBecomeLeader()`。
- Produces: `ScheduleRunRecovery.recover()`（接管：`markStaleRunningFailed()` + `catchUpMissedSingleRuns()`）；`ScheduleRunRecovery.sweep()`（常驻：`releaseStaleInFlight()` + `markStaleRunningFailed()`，仅主节点执行）；实现 `SmartLifecycle` 周期调度（默认 30s）；JobScheduler 构造参数增加 `ScheduleRunRecovery`，HA 开启时 `onBecomeLeader` 先恢复再对账。

- [ ] **Step 1: 写失败测试**

`ScheduleRunRecoveryTest.java`：

```java
package com.wly.job.server.schedule;

import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.service.ScheduleJobService;
import com.wly.job.server.utils.CronUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ScheduleRunRecoveryTest {

    private final JobRep jobRep = mock(JobRep.class);
    private final ScheduleRecRep recRep = mock(ScheduleRecRep.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final SingleRunTracker tracker = new SingleRunTracker();
    private final ScheduleLeaderElector leaderElector = mock(ScheduleLeaderElector.class);

    private ScheduleRunRecovery recovery() {
        ScheduleProps props = new ScheduleProps();
        props.setReqTimeout(30000);
        return new ScheduleRunRecovery(jobRep, recRep, scheduleJobService, tracker, props, leaderElector);
    }

    private Job singleRunJob(long id) {
        return Job.builder().id(id).name("once-" + id).type(1).finished(0).cron("0 0 2 * * ?").build();
    }

    @Test
    void staleRunningRecordsMarkedFail() {
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of());

        recovery().recover();

        verify(recRep).update(isNull(), any());
    }

    @Test
    void missedSingleRunFiredImmediately() {
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(null);

        recovery().recover();

        verify(scheduleJobService).schedule(any(Job.class));
        assertTrue(tracker.contains(1L));
    }

    @Test
    void alreadyDispatchedOccurrenceNotRepeated() {
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", LocalDateTime.now());
        Date dispatchedAt = Date.from(previous.atZone(CronUtils.zone()).toInstant());
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(
                ScheduleRec.builder().jobId(1L).scheduleTime(dispatchedAt).build());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
        assertFalse(tracker.contains(1L));
    }

    @Test
    void failedOccurrenceBeforeLastCronPointIsCaughtUp() {
        LocalDateTime previous = CronUtils.getPreviousExecution("0 0 2 * * ?", LocalDateTime.now());
        Date oldFailure = Date.from(previous.minusDays(1).atZone(CronUtils.zone()).toInstant());
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(singleRunJob(1L)), List.of());
        when(recRep.getOne(any())).thenReturn(
                ScheduleRec.builder().jobId(1L).scheduleTime(oldFailure).status(-1).build());

        recovery().recover();

        verify(scheduleJobService).schedule(any(Job.class));
    }

    @Test
    void normalJobNeverCaughtUp() {
        Job normal = Job.builder().id(2L).name("every").type(0).finished(0).cron("0 0 2 * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(normal), List.of());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
    }

    @Test
    void finishedSingleRunNeverCaughtUp() {
        Job finished = Job.builder().id(3L).name("done").type(1).finished(1).cron("0 0 2 * * ?").build();
        when(jobRep.batchQueryJobsByCursor(0, 1000)).thenReturn(List.of(finished), List.of());

        recovery().recover();

        verify(scheduleJobService, never()).schedule(any());
    }

    // ---- 常驻清扫（sweep）----

    @Test
    void sweepSkipsWhenNotLeader() {
        when(leaderElector.isLeader()).thenReturn(false);

        recovery().sweep();

        verify(recRep, never()).list(any());
        verify(recRep, never()).update(any(), any());
    }

    @Test
    void sweepReleasesStaleInFlightAndMarksFail() {
        when(leaderElector.isLeader()).thenReturn(true);
        tracker.add(1L);
        ScheduleRec stale = ScheduleRec.builder().jobId(1L)
                .scheduleTime(new Date(System.currentTimeMillis() - 60_000)).build();
        when(recRep.list(any())).thenReturn(List.of(stale), List.of());

        recovery().sweep();

        assertFalse(tracker.contains(1L));
        verify(recRep).update(isNull(), any());
    }

    @Test
    void sweepKeepsInFlightWhenFreshRecordExists() {
        when(leaderElector.isLeader()).thenReturn(true);
        tracker.add(1L);
        ScheduleRec stale = ScheduleRec.builder().jobId(1L)
                .scheduleTime(new Date(System.currentTimeMillis() - 60_000)).build();
        ScheduleRec fresh = ScheduleRec.builder().jobId(1L).build();
        when(recRep.list(any())).thenReturn(List.of(stale), List.of(fresh));

        recovery().sweep();

        assertTrue(tracker.contains(1L));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleRunRecoveryTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`ScheduleRunRecovery.java`：

```java
package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.ha.ScheduleLeaderElector;
import com.wly.job.server.service.ScheduleJobService;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在途记录恢复（ADR-0004 决策 #8/#9/#14）：
 * <p>
 * 常驻清扫（sweep，主节点每 ha-stale-sweep-seconds 执行一次）：
 * 1. releaseStaleInFlight——存在陈旧 RUNNING 且无更新在途记录的单次任务，移除 in-flight，
 *    使"按 Cron 自然重试"恢复（仅置 FAIL 不够：in-flight 仍会挡住 reconcile 重新入队）；
 * 2. markStaleRunningFailed——把超过 reqTimeout+5s 的 RUNNING 记录统一置 FAIL（记录卫生）。
 * <p>
 * 接管恢复（recover，成为主时立即执行）：
 * 1. 陈旧 RUNNING 全量置 FAIL（覆盖重启/丢回调场景）；
 * 2. 对未派发过的单次任务补触发一次错过的火点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRunRecovery implements SmartLifecycle {

    private static final long STALE_RUNNING_GRACE_MS = 5000L;

    private final JobRep jobRep;

    private final ScheduleRecRep recRep;

    private final ScheduleJobService scheduleJobService;

    private final SingleRunTracker singleRunTracker;

    private final ScheduleProps props;

    private final ScheduleLeaderElector leaderElector;

    private volatile boolean running = false;

    private ScheduledExecutorService sweepExecutor;

    /**
     * 接管恢复：成为主时立即调用。
     */
    public void recover() {
        markStaleRunningFailed();
        catchUpMissedSingleRuns();
    }

    /**
     * 常驻清扫：仅主节点执行。
     */
    void sweep() {
        if (!leaderElector.isLeader()) {
            return;
        }
        releaseStaleInFlight();
        markStaleRunningFailed();
    }

    void releaseStaleInFlight() {
        long cutoff = staleCutoffMillis();
        List<ScheduleRec> staleRecs = recRep.list(Wrappers.<ScheduleRec>lambdaQuery()
                .select(ScheduleRec::getJobId, ScheduleRec::getScheduleTime)
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .lt(ScheduleRec::getScheduleTime, new Date(cutoff)));
        List<ScheduleRec> freshRecs = recRep.list(Wrappers.<ScheduleRec>lambdaQuery()
                .select(ScheduleRec::getJobId)
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .ge(ScheduleRec::getScheduleTime, new Date(cutoff)));
        Set<Long> freshJobIds = freshRecs.stream().map(ScheduleRec::getJobId).collect(Collectors.toSet());
        staleRecs.stream().map(ScheduleRec::getJobId).distinct()
                .filter(jobId -> !freshJobIds.contains(jobId))
                .forEach(jobId -> {
                    singleRunTracker.remove(jobId);
                    log.warn("Stale in-flight released, jobId: {}", jobId);
                });
    }

    void markStaleRunningFailed() {
        long cutoff = staleCutoffMillis();
        recRep.update(null, Wrappers.<ScheduleRec>lambdaUpdate()
                .eq(ScheduleRec::getStatus, ScheduleRec.RUNNING)
                .lt(ScheduleRec::getScheduleTime, new Date(cutoff))
                .set(ScheduleRec::getStatus, ScheduleRec.FAIL)
                .set(ScheduleRec::getCompleteTime, new Date())
                .set(ScheduleRec::getExecuteResult, "stale running after timeout sweep"));
    }

    private long staleCutoffMillis() {
        return System.currentTimeMillis() - props.getReqTimeout() - STALE_RUNNING_GRACE_MS;
    }

    void catchUpMissedSingleRuns() {
        long offset = 0;
        var jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        while (!jobs.isEmpty()) {
            for (Job job : jobs) {
                if (!isUnfinishedSingleRun(job)) {
                    continue;
                }
                LocalDateTime previous = CronUtils.getPreviousExecution(job.getCron(), LocalDateTime.now());
                if (previous == null) {
                    continue;
                }
                Instant previousInstant = previous.atZone(CronUtils.zone()).toInstant();
                ScheduleRec latest = recRep.getOne(Wrappers.<ScheduleRec>lambdaQuery()
                        .eq(ScheduleRec::getJobId, job.getId())
                        .orderByDesc(ScheduleRec::getScheduleTime)
                        .last("LIMIT 1"));
                if (latest != null && !latest.getScheduleTime().toInstant().isBefore(previousInstant)) {
                    // 该触发点已派发过（RUNNING/FAIL/SUCCESS 均视为已尝试），不重复补
                    continue;
                }
                dispatchCatchUp(job);
            }
            offset = jobs.getLast().getId();
            jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
        }
    }

    private void dispatchCatchUp(Job job) {
        singleRunTracker.add(job.getId());
        try {
            scheduleJobService.schedule(job);
        } catch (Exception e) {
            log.error("single-run catch-up dispatch fail, job: {}", job.getName(), e);
            singleRunTracker.remove(job.getId());
        }
    }

    private boolean isUnfinishedSingleRun(Job job) {
        return job.getType() != null
                && job.getType() == JobTypeEnum.SINGLE.getCode()
                && !Objects.equals(job.getFinished(), 1);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "schedule-stale-run-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1, props.getHaStaleSweepSeconds());
        sweepExecutor.scheduleWithFixedDelay(this::sweep, interval, interval, TimeUnit.SECONDS);
        log.info("ScheduleRunRecovery started, sweep interval: {}s", interval);
    }

    @Override
    public void stop() {
        running = false;
        if (sweepExecutor != null) {
            sweepExecutor.shutdown();
            try {
                if (!sweepExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    sweepExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sweepExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

`JobScheduler.java`：

- 新增字段 `private final ScheduleRunRecovery scheduleRunRecovery;`
- `onBecomeLeader` 替换为：

```java
    @Override
    public void onBecomeLeader() {
        if (scheduleProps.isHaEnabled()) {
            scheduleRunRecovery.recover();
        }
        schedulerEngine.start();
        reconcileQueuedJobs();
        log.info("JobScheduler became leader, queue rebuilt");
    }
```

`JobSchedulerTest.java`：`scheduler()` 增加 `ScheduleRunRecovery recovery = mock(ScheduleRunRecovery.class);` 并把构造调用改为：

```java
        return new JobScheduler(jobRep, scheduleJobService, engine, tracker, props(), leaderElector, recovery);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl admin -am test -Dtest=ScheduleRunRecoveryTest,JobSchedulerTest`
Expected: PASS（18 个用例：ScheduleRunRecoveryTest 9 + JobSchedulerTest 9）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/main/java/com/wly/job/server/schedule/ScheduleRunRecovery.java admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java admin/src/test/java/com/wly/job/server/schedule/ScheduleRunRecoveryTest.java admin/src/test/java/com/wly/job/server/schedule/JobSchedulerTest.java
git commit -m "feat: 常驻清扫陈旧 RUNNING 并释放 in-flight、接管时补触发单次任务"
```

---

### Task A6: 全量回归与验收核对

**Files:**
- 无新增文件；如回归发现问题，修复并随本任务提交。

**Interfaces:**
- Consumes: Task A0-A5 全部改动。
- Produces: 满足 Spec 阶段 A 验收标准的可交付 Admin HA 改造。

- [ ] **Step 1: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 对照 Spec 验收清单核对**

逐条核对 `docs/spec/2026-08-03-admin-ha-spec.md` 第 5 节"阶段 A"的 9 条验收点；单测覆盖不到的（多节点真实抢锁、12s 接管）标注为"待真库/多实例验证"。

- [ ] **Step 3: 残留引用检查**

Run: `rg -n "schedulerEngine.start\(\)" admin/src/main/java/com/wly/job/server/schedule/JobScheduler.java`
Expected: 无输出（engine 启动只存在于 `onBecomeLeader`）。

Run: `rg -n "haEnabled|LeaderElection|ScheduleRunRecovery" admin/src/main/java admin/src/test/java`
Expected: 输出覆盖 A2-A5 的实现与测试，无孤立引用。

- [ ] **Step 4: 如有修复则提交**

```bash
git add -A
git commit -m "fix: HA 回归问题修复"
```

（无修复则跳过。）

---

## Self-Review

**Spec 覆盖：**
- 决策 #2/#3（选主抽象、租约参数）→ Task A2；#4/#5/#6（Standby 行为、切换语义、派发门控）→ Task A3 + A4；#8（错过补触发）→ Task A1 + A5；#9/#14（常驻清扫、回调缺失保障链）→ Task A2（清扫间隔配置）+ A5；#13（开关默认）→ Task A2 配置。
- 决策 #1/#7/#12 为契约与文档决策，由 ADR-0004/ADR-0002 固化，本 Plan 不重复实现。
- Spec 阶段 A 验收 1-9 → A4（验收 1/2/5/6）、A2（验收 7）、A5（验收 4/9）、A1-A5 单测（验收 8）；验收 3（12s 接管）需真库多实例验证，标注为后续集成验证。

**占位符扫描：** 无 TBD/TODO/"适当处理"类占位；所有代码步骤均给出完整代码。

**类型一致性：**
- `LeaderElection.acquireOrRenew()/release()` 在 A2/A3 中一致。
- `ScheduleLeaderElector` Spring 构造 `(LeaderElection, ScheduleProps, ObjectProvider<LeadershipListener>)`、测试构造 `(LeaderElection, ScheduleProps, List<LeadershipListener>)` 在 A3 定义、A3/A4 测试引用一致。
- `JobScheduler` 构造参数序列：A4 为 6 参（+leaderElector），A5 为 7 参（+scheduleRunRecovery），两处测试同步更新。
- `CronUtils.getPreviousExecution(String, LocalDateTime)` 与 `zone()` 在 A1 定义、A5 使用一致。
- `ScheduleLockMapper.acquireOrRenew(owner, leaseSeconds)` 的 SQL 列名与 `docs/sql/schema.sql` 的 `schedule_lock` 表一致（owner/expire_time/id）。
- `ScheduleRunRecovery` 构造 `(JobRep, ScheduleRecRep, ScheduleJobService, SingleRunTracker, ScheduleProps, ScheduleLeaderElector)` 在 A5 测试与实现一致；`props.getHaStaleSweepSeconds()` 在 A2 定义、A5 使用一致。
