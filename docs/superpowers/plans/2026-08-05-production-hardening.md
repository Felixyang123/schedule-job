# 生产加固（Production Hardening）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据 `docs/spec/2026-08-05-production-hardening-spec.md` 落地 10 项生产加固改造：安全鉴权（开放接口 + Worker RPC）、静态资源实例化、回调线程池化、HTTP 超时、Redis 存储对账清理、落库可靠性、可观测性、停机顺序、保留策略。**不改变调度语义**。

**Architecture:** 改造集中在三类：① 资源管理——`ChannelManager` / `ScheduleRequestHandler` / `ScheduleFuture` 从静态单例实例化为 Spring Bean，回调改有界线程池，停机顺序用 `getPhase()` 编排；② 安全——`/open/**` 与 Worker RPC 均校验 token；③ 可靠性/可观测——心跳超时、Redis 索引对账、落库重试、actuator 指标、schedule_rec 保留清理。

**Tech Stack:** Java 21 / Spring Boot 3.5.6 / Netty 4.1.108.Final / MyBatis-Plus 3.5.7 / spring-boot-starter-actuator + micrometer-registry-prometheus（本轮新引入）/ JUnit 5 + Mockito。

## Global Constraints

- JDK 21，构建验证：`JAVA_HOME=C:\Users\wangyang\.jdks\ms-21.0.10 mvn -pl admin -am test`（每次 commit 前）。
- 新增运行时依赖仅限 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`（父 POM 管理版本，无需 version）。
- **不改变调度语义**：At-Least-Once、in-flight/Finished 不变量、变更源增量对账、单活 HA、双发窗口 ≤1s 均保持现状。
- 回调与消费不得在 Netty I/O 线程执行（回调降级执行除外）。
- 鉴权默认拒绝：`schedule.access-token` 未配置时 `/open/**` 一律 401。
- 业务异常派生自 `ScheduleException`；逻辑删除使用 MyBatis-Plus `removeById`。
- 本计划在 `dev` 分支执行，不新建分支。

## File Map

- Create: `admin/.../config/OpenApiTokenInterceptor.java`
- Create: `admin/.../config/WebMvcConfig.java`（注册拦截器）
- Create: `admin/.../metrics/MetricsRegistry.java`（薄封装 Micrometer 的指标名常量 + 便捷埋点）
- Create: `admin/.../schedule/ScheduleRecCleaner.java`（每日清理终态调度记录）
- Modify: `admin/.../client/ChannelManager.java`、`client/handler/ScheduleRequestHandler.java`、`client/future/ScheduleFuture.java`、`client/ScheduleJobClient.java`、`client/handler/ScheduleClientHandler.java`、`client/NettyLifecycle.java`
- Modify: `admin/.../config/ScheduleProps.java`、`config/ScheduleConfiguration.java`、`config/MybatisConfiguration.java`（如需要）
- Modify: `admin/.../schedule/JobScheduler.java`、`schedule/ScheduleRecQueue.java`、`schedule/ScheduleRunRecovery.java`、`schedule/AbstractScheduleService.java`
- Modify: `common/.../bean/ScheduleJobRequest.java`（加 `token` 字段）
- Modify: `core/.../bootstrap/JobInstanceHandler.java`、`core/.../helper/RestClientHelper.java`、`core/.../ScheduleJobCoreFactory.java`
- Modify: `admin/src/main/resources/application-dev.yml`、`application-prod.yml`、`application.yml`（actuator 暴露 + access-token）
- Modify: `job-spring-boot-starter/.../config/ScheduleJobConfigProps.java`（http 超时配置）
- Modify: `admin/pom.xml`（actuator + prometheus 依赖）
- Update tests: `ScheduleRequestHandlerTest` / `NettyRoundTripTest` / `ScheduleRecQueueTest` / `ScheduleFutureTest` 等按实例化改造适配。

---

### Task A: 静态资源实例化 + 回调线程池化（Spec #6 + #2）

**Files:**
- Modify: `admin/.../client/ChannelManager.java`
- Modify: `admin/.../client/handler/ScheduleRequestHandler.java`
- Modify: `admin/.../client/future/ScheduleFuture.java`
- Modify: `admin/.../client/ScheduleJobClient.java`
- Modify: `admin/.../client/handler/ScheduleClientHandler.java`
- Modify: `admin/.../client/NettyLifecycle.java`
- Modify: `admin/.../config/ScheduleProps.java`
- Modify: `admin/.../config/ScheduleConfiguration.java`
- Modify tests: `ScheduleRequestHandlerTest`、`NettyRoundTripTest`、`ScheduleFutureTest`

**Interfaces:**
- Consumes: `ScheduleProps`（新增 `callback-threads`）、`ChannelManager` Bean、`ScheduleRequestHandler` Bean。
- Produces: 三个静态工具类实例化为 `@Component` Bean；`ScheduleFuture` 回调线程池改为注入的有界线程池。

- [ ] **Step 1: `ScheduleProps` 新增 `callbackThreads`（`schedule.callback-threads`，默认 4）**

Run: 检查 `admin/.../config/ScheduleProps.java`
Expected: 新增字段 `private int callbackThreads = 4;`

- [ ] **Step 2: `ChannelManager` 实例化**

改造：去掉 `static` 修饰与静态初始化；`EventLoopGroup`、两个 `ConcurrentMap`、`ScheduleClientHandler` 改为实例字段；类加 `@Component`；`shutdown()` 保留但由 `NettyLifecycle` 调用。
Expected: `getChannelAsync` / `removeChannel` 改为实例方法；调用方（`ScheduleJobClient` / `ScheduleClientHandler`）改为注入 Bean。

- [ ] **Step 3: `ScheduleRequestHandler` 实例化**

改造：去掉静态字段；`REQUEST_MAP` / `TIMEOUT_MAP` / `CHANNEL_REQ_IDS_MAP` / `CLEANUP_EXECUTOR` / `CLEANUP_STARTED` 改为实例字段；`startCleanupIfNeeded` 保留惰性启动；`shutdown()` 清空 Map 并停清理线程。类加 `@Component`。
Expected: `put` / `complete` / `completeExceptionally` / `cleanupAllRequests` 改为实例方法；`ScheduleJobClient`、`ScheduleClientHandler` 注入调用。

- [ ] **Step 4: `ScheduleFuture` 回调线程池化**

改造：移除静态 `CALLBACK_EXECUTOR`；`dispatchCallbacks` 使用通过构造传入的 `ExecutorService`；构造签名增加线程池参数；队列满时 `RejectedExecutionException` 降级为**当前线程直接执行回调**。
Expected: `new ScheduleFuture<>(timeout, channel, callbackExecutor)`；`dispatchCallbacks` 内 catch `RejectedExecutionException` 后 `run()` 而非 `execute()`。

- [ ] **Step 5: `ScheduleJobClient` 注入改造**

改造：`record` 改为注入 `ChannelManager`、`ScheduleRequestHandler`、回调线程池（`ExecutorService`，由 `ScheduleConfiguration` 按 `callbackThreads` 创建有界线程池，队列 1024）；`send()` 中创建 `ScheduleFuture` 时传入线程池。
Expected: `ScheduleJobClient` 不再引用静态方法。

- [ ] **Step 6: `ScheduleClientHandler` 注入改造**

改造：`channelInactive` 调用的 `ChannelManager.removeChannel` 改为注入调用。
Expected: 无静态引用。

- [ ] **Step 7: `NettyLifecycle` 统一管理**

改造：注入三个 Bean，`stop()` 依次调用 `ChannelManager.shutdown()` → `ScheduleRequestHandler.shutdown()` → 回调线程池 `shutdown()`。
Expected: 停止顺序正确、无资源泄漏。

- [ ] **Step 8: 测试适配与验证**

改造：`ScheduleRequestHandlerTest` / `NettyRoundTripTest` / `ScheduleFutureTest` 改为实例化测试对象（`new ScheduleRequestHandler(...)` / `new ChannelManager(...)`）。
Run: `mvn -pl admin -am test`
Expected: BUILD SUCCESS，全部测试通过。

---

### Task B: 开放接口鉴权（Spec #1）

**Files:**
- Create: `admin/.../config/OpenApiTokenInterceptor.java`
- Create: `admin/.../config/WebMvcConfig.java`
- Modify: `admin/.../config/ScheduleProps.java`
- Modify: `admin/src/main/resources/application-dev.yml`、`application-prod.yml`

**Interfaces:**
- Consumes: `ScheduleProps.accessToken`。
- Produces: `/open/**` 鉴权拦截器。

- [ ] **Step 1: `ScheduleProps` 新增 `accessToken`（`schedule.access-token`）**

Run: 检查 `ScheduleProps.java`
Expected: 新增字段 `private String accessToken;`

- [ ] **Step 2: 新建 `OpenApiTokenInterceptor`**

实现 `HandlerInterceptor`：仅拦截 `/open/**`；取 `Authorization` 头，剥离 `Bearer ` 前缀；**`accessToken` 未配置 → 一律返回 401**；已配置 → 不匹配返回 401 + `Result.fail("unauthorized")`，并 `log.warn` 记录来源 IP。
Expected: `preHandle` 返回 false 且写 401 响应。

- [ ] **Step 3: `WebMvcConfig` 注册拦截器**

`addInterceptors` 中 `.addPathPatterns("/open/**")`。
Expected: 拦截器仅对 `/open/**` 生效。

- [ ] **Step 4: 配置文件补充 token**

dev：`schedule.access-token: defaultToken`（与 sample 的 `accessToken: defaultToken` 对齐）；prod：`schedule.access-token: ${SCHEDULE_ACCESS_TOKEN}`。
Expected: 配置生效。

- [ ] **Step 5: 验证**

Run: 启动 Admin，未带/带错 token 请求 `/open/job/register` → 401；带正确 token → 放行。
Expected: 行为符合验收标准 1。

---

### Task C: Worker RPC 鉴权（Spec #9）

**Files:**
- Modify: `common/.../bean/ScheduleJobRequest.java`
- Modify: `admin/.../schedule/AbstractScheduleService.java`
- Modify: `admin/.../config/ScheduleProps.java`（如 Task B 已加则复用）
- Modify: `core/.../bootstrap/JobInstanceHandler.java`
- Modify: `core/.../ScheduleJobCoreFactory.java`
- Modify: `core/.../selector/`（如构造受影响）
- Modify tests: `NettyRoundTripTest`

**Interfaces:**
- Consumes: `schedule.access-token`（Admin 派发）、`schedule-job.accessToken`（Worker 校验）。
- Produces: `ScheduleJobRequest.token` 字段、Worker 侧校验逻辑。

- [ ] **Step 1: `ScheduleJobRequest` 增加 `token` 字段**

Expected: 新增 `private String token;`（JSON 编解码天然兼容）。

- [ ] **Step 2: Admin 派发携带 token**

`AbstractScheduleService.schedule` 构建 `ScheduleJobRequest` 时 `.token(scheduleProps.getAccessToken())`；`AbstractScheduleService` 注入 `ScheduleProps`。
Expected: 派发请求带 token。

- [ ] **Step 3: Worker 侧校验**

`JobInstanceHandler` 构造增加 `expectedToken` 参数（来自 `ScheduleJobCoreFactory` 传入的 `accessToken`）；`handleRequest` 中若 `expectedToken` 非空且请求 `token` 不匹配 → 返回 `success=false` 并 `ctx.close()` 断开连接。
Expected: 错误 token 被拒并断开；空 `expectedToken`（未配置）时不校验（兼容旧部署）。

- [ ] **Step 4: `ScheduleJobCoreFactory` 传递 token**

构造器把 `accessToken` 传入 `new JobInstanceHandler(registry, accessToken)`。
Expected: 无编译错误。

- [ ] **Step 5: 验证**

Run: `mvn -pl admin -am test`；手工验证正确/错误 token 请求。
Expected: 验收标准 9 通过。

---

### Task D: Worker 心跳 HTTP 超时（Spec #3）

**Files:**
- Modify: `core/.../helper/RestClientHelper.java`
- Modify: `job-spring-boot-starter/.../config/ScheduleJobConfigProps.java`
- Modify: `core/.../ScheduleJobCoreFactory.java`

**Interfaces:**
- Consumes: `schedule-job.http-connect-timeout` / `http-read-timeout`。
- Produces: `RestClient` 带超时配置。

- [ ] **Step 1: `RestClientHelper` 设置超时**

`RestClient.builder()` 链式配置 `requestFactory`（`JdkClientHttpRequestFactory` 或 `ClientHttpRequestFactorySettings` 设置 connect/read timeout）。
Expected: 建连/读超时生效，超时抛 `RestClientException` → 转为 `ScheduleException`。

- [ ] **Step 2: `ScheduleJobConfigProps` 新增超时配置**

`httpConnectTimeout`（默认 2000）、`httpReadTimeout`（默认 3000），Javadoc 固化约束公式：`单次尝试 ≤ (租约剔除时间 − 心跳间隔) / Admin 节点数`，示例 30s/10s/5 节点 → 4s。
Expected: 配置键 `schedule-job.http-connect-timeout` / `http-read-timeout`。

- [ ] **Step 3: `ScheduleJobCoreFactory` 传递超时**

构造器把超时值传给 `RestClientHelper.builder()`。
Expected: 无编译错误。

- [ ] **Step 4: 验证**

Run: `mvn -pl admin -am test`。
Expected: 验收标准 3 通过。

---

### Task E: Redis 实例存储对账清理（Spec #4）

**Files:**
- Modify: `admin/.../stroage/RedisJobInstanceStorage.java`
- Modify: `admin/.../config/ScheduleConfiguration.java`（如需要注册清理组件）

**Interfaces:**
- Consumes: `StringRedisTemplate`。
- Produces: `list()` 过滤 null；周期对账清理。

- [ ] **Step 1: `list()` 过滤 null**

`instanceJsons.stream().filter(Objects::nonNull).map(this::deserialize).toList()`。
Expected: 过期索引不再触发 NPE。

- [ ] **Step 2: 新增周期对账清理**

`RedisJobInstanceStorage` 增加 `clearExpired()`：扫描 `job:services` → 各 `job:service:{key}` 成员 → `GET` 实例详情为 null 才从索引 Set 删除；类实现 `SmartLifecycle` 启动 30s 周期任务（与 `LocalCacheJobInstanceStorage` 对齐）。**读路径不删索引**。
Expected: 以详情为权威的对账，脏键被清理。

- [ ] **Step 3: 验证**

Run: `mvn -pl admin -am test`；构造过期索引场景单测。
Expected: 验收标准 4 通过。

---

### Task F: ScheduleRec 落库可靠性（Spec #5）

**Files:**
- Modify: `admin/.../schedule/ScheduleRecQueue.java`
- Modify: `admin/.../config/ScheduleProps.java`（如需要容量配置）

**Interfaces:**
- Consumes: 无新依赖。
- Produces: 失败重试 + 队列上限。

- [ ] **Step 1: 失败重试**

`flush()` 中 `saveBatch` / `update` 失败时不 clear 批次，退避重试（最多 3 次，如 100/500/1000ms），仍失败 `log.error` + 计数累加（预留 MetricsRegistry 埋点）。
Expected: 不丢批、不崩溃。

- [ ] **Step 2: 队列上限**

`LinkedBlockingQueue` 构造传容量（10000）；`offer` 失败（满）时丢弃并 `log.warn`（不阻塞）。
Expected: 打满丢弃新记录，派发主链路不受影响。

- [ ] **Step 3: 验证**

Run: `mvn -pl admin -am test`。
Expected: 验收标准 5 通过。

---

### Task G: 可观测性（Spec #7）

**Files:**
- Modify: `admin/pom.xml`
- Modify: `admin/src/main/resources/application.yml`、`application-dev.yml`
- Create: `admin/.../metrics/MetricsRegistry.java`

**Interfaces:**
- Consumes: micrometer（actuator 传递引入）。
- Produces: `/actuator/prometheus` 指标。

- [ ] **Step 1: `admin/pom.xml` 增加依赖**

`spring-boot-starter-actuator`、`micrometer-registry-prometheus`。
Expected: 依赖树含 micrometer-core + prometheus registry。

- [ ] **Step 2: `application.yml` 暴露端点**

`management.endpoints.web.exposure.include: health,info,metrics,prometheus`；`management.endpoint.health.show-details: always`。
Expected: `/actuator/prometheus` 可访问。

- [ ] **Step 3: 新建 `MetricsRegistry`**

薄封装：指标名常量 + `Counter` / `Gauge` 便捷方法（`MeterRegistry` 注入）。
Expected: 统一指标入口。

- [ ] **Step 4: 埋点**

- `JobScheduler`：引擎队列积压 Gauge（`queuedJobs.size()`）、变更源水印滞后 Gauge（`maxId - watermark`）；
- `ScheduleRecCallback`：回调成功/失败 Counter；
- `ScheduleJobClient`：派发延迟 Timer（`take` 到 send）；
- `ScheduleRecQueue`：落库失败 Counter（Task F 预留）、丢弃 Counter；
- `DefaultRemoteJobRegistry`：心跳失败 Counter。
Expected: `/actuator/prometheus` 可见上述指标。

- [ ] **Step 5: 验证**

Run: 启动 Admin，`curl localhost:8100/actuator/prometheus`。
Expected: 验收标准 7 通过。

---

### Task H: SmartLifecycle 停机顺序（Spec #8）

**Files:**
- Modify: `admin/.../schedule/JobScheduler.java`
- Modify: `admin/.../client/NettyLifecycle.java`

**Interfaces:**
- Consumes: 无。
- Produces: 显式 phase。

- [ ] **Step 1: `JobScheduler.getPhase()`**

返回 `Integer.MAX_VALUE - 10`（最先停）。
Expected: 停派发/对账最先执行。

- [ ] **Step 2: `NettyLifecycle.getPhase()`**

返回 `Integer.MIN_VALUE + 10`（最后停，紧邻 `ScheduleLeaderElector` 的 `MIN_VALUE` 之前）。
Expected: 关连接/回调线程最后执行。

- [ ] **Step 3: 验证**

Run: 停止应用，观察日志顺序：JobScheduler → ScheduleRecQueue → NettyLifecycle → ScheduleLeaderElector。
Expected: 验收标准 8 通过。

---

### Task I: schedule_rec 保留策略（Spec #10）

**Files:**
- Modify: `admin/.../config/ScheduleProps.java`
- Create: `admin/.../schedule/ScheduleRecCleaner.java`
- Modify: `admin/.../config/ScheduleConfiguration.java`

**Interfaces:**
- Consumes: `schedule.rec-retention-days`（默认 7）、`ScheduleRecRep`。
- Produces: 每日清理终态记录。

- [ ] **Step 1: `ScheduleProps` 新增 `recRetentionDays`（默认 7）**

Expected: 字段 `private int recRetentionDays = 7;`

- [ ] **Step 2: 新建 `ScheduleRecCleaner`**

实现 `SmartLifecycle`：`ScheduledExecutorService` 每日一次（`scheduleWithFixedDelay`，延迟 24h）；`delete` 条件：`complete_time < now - retention AND status IN (FAIL, SUCCESS)`；**RUNNING 不删**。
Expected: 终态记录按保留期清理。

- [ ] **Step 3: 验证**

Run: `mvn -pl admin -am test`；单测覆盖"只删终态"。
Expected: 验收标准 10 通过。

---

### Task J: 收尾（文档 + 全量验证）

**Files:**
- Modify: `AGENTS.md`（新增 actuator/prometheus 依赖豁免说明、HTTP 超时公式、鉴权默认拒绝说明）
- Modify: `CONTEXT.md`（如新增术语，如无则跳过）

**Interfaces:**
- Consumes: 本计划全部 Task 成果。

- [ ] **Step 1: 更新 `AGENTS.md`**

- §5.1 依赖约束：注明"生产加固允许 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`"；
- §0.4 配置键：补充 `schedule.access-token`、`schedule.callback-threads`、`schedule.rec-retention-days`、`schedule-job.http-*`。
Expected: 文档与代码一致。

- [ ] **Step 2: 全量验证**

Run: `mvn -pl admin -am test`
Expected: BUILD SUCCESS，全部测试通过。

- [ ] **Step 3: 最终提交**

Run: `git add -A && git commit`
Expected: 一次提交覆盖全部改造（或按 Task 分组多次提交，遵循既有 commit 风格）。
