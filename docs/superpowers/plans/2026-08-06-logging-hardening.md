# 日志完善（Logging Hardening）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据 `docs/spec/2026-08-06-logging-hardening-spec.md` 落地日志完善 5 项（A 补点 / B HTTP 请求日志 / C JSON 结构化 / D MDC 链路追踪 / E 文件归档异步化）。**不改变调度语义**。

**Architecture:** ① common 模块新增 `MdcTaskDecorator`（纯 JDK，跨线程 MDC 快照捕获/恢复）；② admin 新增 `RequestLogFilter`（合并 requestId 生成 + 请求日志）；③ admin/core/starter 三模块新增 `logback-spring.xml`（`<springProfile>` 切 prod JSON / dev 文本，异步 appender + 30 天归档）；④ 关键路径补日志（成功 debug / 失败 warn）；⑤ 三模块 pom 引入 `logstash-logback-encoder:8.1`。

**Tech Stack:** Java 21 / Spring Boot 3.5.6 / logback 1.5.x / logstash-logback-encoder 8.1（本轮新引入）/ JUnit 5 + Mockito。

## Global Constraints

- JDK 21，构建验证：`JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10' mvn -pl admin -am test`（每次 commit 前）。
- 新增运行时依赖仅限 `logstash-logback-encoder:8.1`（admin/core/starter），显式版本号。
- **不改变调度语义**：日志改造纯可观测性。
- MDC key 统一 `requestId`；`MdcTaskDecorator` 的 Javadoc 须记录"与虚拟线程化兼容"（虚拟线程间无自动 MDC 传播、恢复快照策略双模型下均正确、未来仅需换线程工厂）。
- 成功类高频日志 debug；失败类低频日志 warn/error；`ScheduleRecCallback` 置 Finished 失败（update 影响 0 行）用 warn。
- `RequestLogFilter`：覆盖 `/admin/**` + `/open/**`，排除 `/actuator/**`；不记 body；`X-Request-Id` 头截断 64 字符；响应头回传。
- 日志 IO 不得阻塞调度主链路（异步 appender）。
- 本计划在 `dev` 分支执行，不新建分支。

## File Map

- Create: `common/src/main/java/com/wly/job/common/logging/MdcTaskDecorator.java`
- Create: `common/src/test/java/com/wly/job/common/logging/MdcTaskDecoratorTest.java`
- Create: `admin/src/main/java/com/wly/job/server/filter/RequestLogFilter.java`
- Create: `admin/src/test/java/com/wly/job/server/filter/RequestLogFilterTest.java`
- Create: `admin/src/main/resources/logback-spring.xml`、`core/src/main/resources/logback-spring.xml`、`job-spring-boot-starter/src/main/resources/logback-spring.xml`
- Modify: `admin/pom.xml`、`core/pom.xml`、`job-spring-boot-starter/pom.xml`（logstash-logback-encoder）
- Modify: `admin/.../schedule/JobScheduler.java`（worker 提交装饰 + 补点）
- Modify: `admin/.../client/future/ScheduleFuture.java`（回调提交装饰）
- Modify: `core/.../bootstrap/JobInstanceHandler.java`（业务线程池装饰）
- Modify: `job-spring-boot-starter/.../processor/ScheduleJobAnnotationProcessor.java`（心跳循环装饰）
- Modify: `admin/.../client/callback/ScheduleRecCallback.java`（onSuccess/onFailure/置位失败补点）
- Modify: `admin/.../schedule/AbstractScheduleService.java`（候选数 debug）
- Modify: `admin/.../service/ScheduleJobService.java`（注册成功 info / DuplicateKey debug）
- Modify: `core/.../registry/DefaultRemoteJobRegistry.java`（成功 debug）
- Modify: `admin/.../schedule/ScheduleRunRecovery.java`（清扫结果 info）
- Modify: `admin/.../schedule/ScheduleRecQueue.java`（落库条数 debug）
- Modify: `AGENTS.md`（§5.1 依赖豁免扩展）

---

### Task 1: `MdcTaskDecorator`（common 模块）

**Files:**
- Create: `common/src/main/java/com/wly/job/common/logging/MdcTaskDecorator.java`
- Create: `common/src/test/java/com/wly/job/common/logging/MdcTaskDecoratorTest.java`

**Interfaces:**
- Consumes: slf4j `MDC`（已传递依赖）。
- Produces: `Runnable decorate(Runnable)` + `ExecutorService wrap(ExecutorService)`（可选便捷方法）。

- [ ] **Step 1: 实现 `MdcTaskDecorator`**

静态工具类。核心方法：
```java
public static Runnable decorate(Runnable task) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return () -> {
        Map<String, String> prev = MDC.getCopyOfContextMap();
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
        try {
            task.run();
        } finally {
            if (prev == null || prev.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(prev);
            }
        }
    };
}
```
- `wrap(ExecutorService)`：`submit/execute/invokeAll` 前套 `decorate`。
- **Javadoc 必须记录虚拟线程兼容性**（Spec 2.3）：
  - 虚拟线程间无自动 MDC 传播，装饰器在虚拟线程模式下仍必要；
  - "恢复快照"策略在平台线程池（防污染）与虚拟线程（无复用）下均正确；
  - 未来虚拟线程化仅需换线程工厂，装饰器原位复用。

- [ ] **Step 2: 单测**

`MdcTaskDecoratorTest`：
- 任务内 MDC 值可见（父线程 put → 任务内 get 一致）；
- 任务内 put 的值执行后**不泄漏**到复用线程（同线程跑两个任务，第二个任务看不到第一个的残留）；
- 父线程无 MDC 时任务内 clear，不抛异常；
- finally 恢复父线程原快照（嵌套场景）。
Run: `mvn -pl common test`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

`feat(common): MdcTaskDecorator 跨线程 MDC 快照传递（兼容虚拟线程）`

---

### Task 2: `RequestLogFilter`（admin 模块）

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/filter/RequestLogFilter.java`
- Create: `admin/src/test/java/com/wly/job/server/filter/RequestLogFilterTest.java`

**Interfaces:**
- Consumes: slf4j MDC。
- Produces: `/admin/**` + `/open/**` 请求日志 + `X-Request-Id` 透传。

- [ ] **Step 1: 实现 `RequestLogFilter`**

`@Component implements Filter`，注册 `urlPatterns=/*`：
- `preHandle` 等价逻辑：取 `X-Request-Id` 头（截断 64 字符），缺失则 `UUID` 生成；`MDC.put("requestId", id)`；`response.setHeader("X-Request-Id", id)`；记开始时间。
- `afterCompletion`：若 path 以 `/actuator/` 开头 → **不记日志**（但仍清 MDC）；否则记 `info`：`method={} url={} status={} cost={}ms requestId={} clientIp={}`。
- `finally`：`MDC.remove("requestId")`。
- 说明：Servlet `Filter` 的 `doFilter` 一次调用同时包含请求与响应，故不需要 preHandle/afterCompletion 两段式，在 `doFilter` 里包 try/finally 即可。

- [ ] **Step 2: 单测**

`RequestLogFilterTest`（MockHttpServletRequest/Response）：
- `/open/job/register` 请求 → 日志含 method/url/status/cost/requestId/clientIp；
- `/actuator/health` → **无**请求日志；
- 带 `X-Request-Id: abc` → MDC 值为 `abc`，响应头回传 `abc`；
- 不带头 → 生成 UUID，响应头回传；
- 超长头（>64 字符）→ 截断。
Run: `mvn -pl admin -am test -Dtest=RequestLogFilterTest`
Expected: 通过。

- [ ] **Step 3: 提交**

`feat(admin): RequestLogFilter 统一请求日志与 X-Request-Id 透传`

---

### Task 3: 三模块引入 logstash-logback-encoder + logback-spring.xml

**Files:**
- Modify: `admin/pom.xml`、`core/pom.xml`、`job-spring-boot-starter/pom.xml`
- Create: `admin/src/main/resources/logback-spring.xml`、`core/src/main/resources/logback-spring.xml`、`job-spring-boot-starter/src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: logstash-logback-encoder 8.1。
- Produces: prod JSON / dev 文本 + 异步 + 归档。

- [ ] **Step 1: 三模块 pom 加依赖**

`net.logstash.logback:logstash-logback-encoder:8.1`（显式版本）。
Expected: `mvn -pl admin -am dependency:tree` 含 logstash-logback-encoder 8.1 与 logback 1.5.x。

- [ ] **Step 2: 编写 `logback-spring.xml`（以 admin 为基准，core/starter 复制同构）**

结构（`<springProfile>` 区分）：
```xml
<configuration>
  <springProperty name="appName" source="spring.application.name"/>
  <property name="LOG_DIR" value="logs/${appName}"/>

  <!-- 异步共享（prod 与 dev 共用同一异步 appender，内部按 profile 路由） -->
  <!-- 或：prod 用 AsyncAppender 包 JSON file appender；dev 用 ConsoleAppender 文本 -->

  <springProfile name="prod">
    <!-- JSON file appender: logs/{appName}/{appName}-{yyyy-MM-dd}.log
         LogstashEncoder + includeLevelValue=true + MDC requestId -->
    <!-- AsyncAppender: queueSize=8192 neverBlock=true -->
  </springProfile>
  <springProfile name="!prod">
    <!-- ConsoleAppender 文本 pattern: %d{...} %-5level [%thread] [%X{requestId:-}] %logger{36} - %msg%n -->
    <!-- 同时输出到文件（同路径），便于 dev 排查 -->
  </springProfile>

  <root level="INFO">
    <appender-ref ref="ASYNC"/> <!-- prod 走 JSON 文件，dev 走控制台+文件 -->
  </root>
</configuration>
```
关键点：
- 滚动：`TimeBasedRollingPolicy`，`fileNamePattern=${LOG_DIR}/archive/${appName}-%d{yyyy-MM-dd}.%i.log.zip`，`maxHistory=30`，`totalSizeCap=10GB`，`maxFileSize=500MB`。
- **`%X{requestId:-}` 必须出现在 dev pattern**。
- **prod 日志级别**：维持 `com.wly.job.server` 现有 info；dev 维持 debug。
- 三模块配置同构（同一套规则），不开放个性化。

- [ ] **Step 3: 验证**

Run: `JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10' mvn -pl admin -am test`（编译期验证配置合法）；dev profile 启动验证文本输出 + `logs/{appName}/` 文件生成。
Expected: BUILD SUCCESS + dev 下日志落文件。

- [ ] **Step 4: 提交**

`feat(logging): 三模块 logback-spring.xml（prod JSON / dev 文本 + 异步 + 30 天归档）`

---

### Task 4: 关键路径日志补点（A 项）

**Files:**
- Modify: `admin/.../schedule/JobScheduler.java`
- Modify: `admin/.../client/callback/ScheduleRecCallback.java`
- Modify: `admin/.../schedule/AbstractScheduleService.java`
- Modify: `admin/.../service/ScheduleJobService.java`
- Modify: `core/.../registry/DefaultRemoteJobRegistry.java`
- Modify: `admin/.../schedule/ScheduleRunRecovery.java`
- Modify: `admin/.../schedule/ScheduleRecQueue.java`

**Interfaces:**
- Consumes: 无新依赖。
- Produces: 补点日志（成功 debug / 失败 warn，见 Spec 2.5）。

- [ ] **Step 1: 按 Spec 2.5 清单逐点补日志**

| 位置 | 级别 | 内容 |
|---|------|------|
| `JobScheduler.handle` 派发成功 | debug | `jobId name requestId -> instance:port` |
| `ScheduleRecCallback.onSuccess` | debug | `requestId jobId -> SUCCESS` |
| `ScheduleRecCallback.onFailure` | warn | `requestId jobId -> FAIL` + 异常 |
| `ScheduleRecCallback` 置 Finished 失败 | warn | update 影响 0 行（type=SINGLE 守卫命中） |
| `AbstractScheduleService` | debug | `job -> candidates=N` |
| `JobScheduler.applyChange` | debug | `jobId changeType -> add/remove/replace/skip` |
| `ScheduleJobService.registerJob` 成功 | info | `group:name` 注册成功；DuplicateKey → debug |
| `DefaultRemoteJobRegistry` 成功 | debug | `path key -> adminAddr OK` |
| `ScheduleRunRecovery` 清扫 | info | `released=N staleRunning=N` |
| `ScheduleRecQueue` 每批 | debug | 条数 |

注意：
- 所有补点**不改变现有逻辑**，仅插入日志语句。
- `onFailure` 的 warn 必须携带异常对象（`log.warn(..., cause)`），保证堆栈进 JSON `stack_trace`。
- `JobScheduler.handle` 补 debug 时需拿到 `instance`（在 `scheduleJobService.schedule` 内部选中，可在补点处用 `ScheduleContext` 或仅记 `jobId/name/requestId`，避免重构取 instance 链路——**如取不到 instance 就只记 jobId/name/requestId**，标记为已知简化）。

- [ ] **Step 2: 验证**

Run: `mvn -pl admin -am test`
Expected: BUILD SUCCESS，全部测试通过（补点不影响断言）。

- [ ] **Step 3: 提交**

`feat(logging): 关键路径日志补点（成功 debug / 失败 warn）`

---

### Task 5: `MdcTaskDecorator` 接入四处跨线程点（D 项落地）

**Files:**
- Modify: `admin/.../schedule/JobScheduler.java`
- Modify: `admin/.../client/future/ScheduleFuture.java`
- Modify: `core/.../bootstrap/JobInstanceHandler.java`
- Modify: `job-spring-boot-starter/.../processor/ScheduleJobAnnotationProcessor.java`

**Interfaces:**
- Consumes: `MdcTaskDecorator`（common，Task 1）。
- Produces: 四处提交点的任务被装饰，MDC requestId 跨线程串联。

- [ ] **Step 1: `JobScheduler` worker 提交装饰**

`scheduleWorkers[i].execute(MdcTaskDecorator.decorate(() -> handle(scheduleJob)))`。
派发线程侧需在提交前把当前 requestId 放 MDC——派发侧本身由 `RequestLogFilter`/上游设置了 MDC，若无则在 `handle` 内 put。

- [ ] **Step 2: `ScheduleFuture` 回调提交装饰**

`CALLBACK_EXECUTOR.execute(MdcTaskDecorator.decorate(() -> { for (...) { ... } }))`。
注意：回调线程拿到的是"派发侧快照"，其 requestId 来自 `ScheduleJobRequest` 创建时的 MDC——若派发线程无 MDC，需在回调入口按 requestId 显式 put（见 Step 4 说明）。

- [ ] **Step 3: `JobInstanceHandler` 业务线程池装饰**

`executorService.submit(MdcTaskDecorator.decorate(() -> { ... }))`。
**关键**：Worker 侧收到 `ScheduleJobRequest` 时，requestId 在**请求对象**里而非当前线程 MDC。因此 `JobInstanceHandler.channelRead0` 需先把 `request.getRequestId()` 放入当前线程 MDC 再 `decorate` 提交：
```java
MDC.put("requestId", request.getRequestId());
executorService.submit(MdcTaskDecorator.decorate(() -> { ... finally { MDC.remove("requestId"); } }));
```
这样 Worker 业务日志携带 Admin 派发的同一 requestId。

- [ ] **Step 4: `ScheduleJobAnnotationProcessor` 心跳/注册循环装饰**

心跳循环是常驻线程，每次 `JobInstanceRegisterTask.run()` 生成一个心跳 requestId 放 MDC（或不放——心跳是系统行为，无业务链路）。**推荐**：心跳注册到 Admin 的 HTTP 请求日志由 `RequestLogFilter` 生成自己的 requestId；本地心跳循环**不强制** MDC。若装饰则透传空快照，无收益——**此点标记为"装饰但不强制 MDC"**（与 Spec 2.3 包装点 ④ 一致）。

- [ ] **Step 5: 验证**

Run: `mvn -pl admin -am test`
Expected: BUILD SUCCESS。手工冒烟：HTTP 请求 → 派发 → Worker 执行 → 回调日志含同一 requestId。

- [ ] **Step 6: 提交**

`feat(logging): MdcTaskDecorator 接入派发/回调/Worker 业务线程`

---

### Task 6: AGENTS.md 更新 + 全量验证（收尾）

**Files:**
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: 本计划全部 Task 成果。

- [ ] **Step 1: 更新 `AGENTS.md`**

- §5.1 依赖豁免：扩展为"`spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `logstash-logback-encoder`（仅 admin/core/starter，日志结构化）"；
- §0.4 或新增小节：MDC `requestId`、`RequestLogFilter`、`MdcTaskDecorator`、logback-spring.xml 环境策略；
- §7 文档索引：追加 `2026-08-06-logging-hardening-spec`。

- [ ] **Step 2: 全量验证**

Run: `mvn -pl admin -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 最终提交**

Run: `git add -A && git commit`
Expected: 一次提交或按 Task 分组提交（遵循既有 commit 风格）。
