# 审计问题全部修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清除本轮审计发现的 E2E 安全风险、注册解耦缺陷、测试假绿、MDC 违规和生命周期测试缺口。

**Architecture:** 四条文件边界互不重叠并行执行：E2E 脚本安全与断言、Worker 注册异常隔离及 Starter 测试、时间轮测试与生命周期验证、MDC/Cleaner/IT 测试基础设施。完成后串行执行全量验证和独立复审。

**Tech Stack:** Java 21、JUnit 5、Mockito、Spring Boot 3.5.6、MyBatis-Plus、MySQL 8、Bash/Git Bash on Windows。

## Global Constraints

- 不引入新的运行时依赖。
- 只 mock 外部网络、Redis、时间、随机；核心调度/注册/SQL 逻辑必须真实执行。
- 不修改用户未要求的业务语义；任何生产代码修复必须有回归测试。
- E2E 只能清理本脚本启动的进程和白名单测试库，禁止按端口盲杀外部进程。
- `RemoteJobRegistry` 的 `instanceKey` 仅用于 Admin 节点选择，不进入 HTTP DTO。
- 实例注册失败不得阻断作业注册和心跳启动。
- 线程池遵守 `MdcExecutorService` 与优雅停机规范。
- 不提交、不推送，除非用户另行明确要求。

---

### Task 1: 加固 E2E 脚本

**Files:**
- Modify: `scripts/e2e-smoke-test.sh`

**Interfaces:** 保持脚本入口 `bash scripts/e2e-smoke-test.sh` 不变；保留 `PASS/FAIL` 汇总。

- [ ] **Step 1: 增加测试库和端口护栏**
  - 只允许 `DB_NAME=job_test`，其他值在前置阶段失败退出。
  - 增加 `ensure_port_free()`，检查 `${ADMIN_PORT}`、`${SAMPLE_PORT}`、8101；若已有 LISTENING 进程，打印 PID 并退出，不调用 `taskkill`。
  - `cleanup()` 只终止 `ADMIN_PID`、`SAMPLE_PID` 以及脚本记录的 Netty PID；不再按端口搜索并强杀未知进程。

- [ ] **Step 2: 区分关键失败和功能失败**
  - 保留普通 `check()` 累计失败。
  - 新增 `fatal_check()`，失败时打印日志并执行退出；Admin 健康检查、Worker 启动检查和每次 SQL 写操作使用 fatal。
  - 将文件头“失败中途退出”与实际行为统一。

- [ ] **Step 3: 修复调度真实性断言**
  - 首次调度前查询本轮 `DemoJob%` 的 job id 集合或直接使用子查询限定 `job_id`。
  - Worker 重启前保存成功记录数 `BEFORE_RESTART`；重启后等待并断言 `AFTER_RESTART > BEFORE_RESTART`。
  - 所有 `schedule_rec` 断言限定 `job_id IN (SELECT id FROM job WHERE name LIKE 'DemoJob%')`。
  - 逻辑删除 SQL 使用明确失败检查，避免 SQL 失败后继续重启。

- [ ] **Step 4: Shell 静态检查**
  - 运行 `bash -n scripts/e2e-smoke-test.sh`。
  - 使用文本检查确认不存在 `kill_by_port` 对未知 PID 执行 `taskkill` 的路径。

---

### Task 2: 修复注册失败隔离并补 Starter 验收测试

**Files:**
- Modify: `job-spring-boot-starter/src/main/java/com/wly/job/starter/processor/ScheduleJobAnnotationProcessor.java`
- Modify: `samples/register-center-registry-sample/src/main/java/com/wly/job/samples/center/registry/RegisterCenterInstanceRegistry.java`
- Create: `job-spring-boot-starter/src/test/java/com/wly/job/starter/processor/ScheduleJobAnnotationProcessorTest.java`

**Interfaces:** 继续使用 `RemoteJobRegistry.register(JobInstance)` 和 `register(JobInfo, String)`；不改变公共 DTO。

- [ ] **Step 1: 写失败测试**
  - 用真实 `ScheduleJobAnnotationProcessor` 扫描测试 Bean，mock `ScheduleJobCoreFactory` 的远程边界。
  - 用 `InOrder` 断言每个实例注册发生在作业注册前。
  - GROUP 模式扫描多个 `@ScheduleJob` 方法，断言实例注册一次、作业注册 N 次。
  - 让实例注册抛 `RuntimeException`，断言作业注册仍执行 N 次且心跳任务已建立。

- [ ] **Step 2: 实现逐实例异常隔离**
  - Processor 步骤①对每个实例使用独立 `try/catch`，记录 discoveryKey 和异常，继续下一个实例。
  - 步骤②和步骤③保持在实例失败后继续执行。
  - 注册中心 sample 的 `register(JobInstance)` 同样捕获并记录外部注册中心异常，避免实现差异。

- [ ] **Step 3: 验证测试**
  - 运行 `mvn -pl job-spring-boot-starter -am -Dtest=ScheduleJobAnnotationProcessorTest test`。
  - 运行 starter 全部单测。

---

### Task 3: 补齐时间轮与引擎生命周期测试

**Files:**
- Modify: `common/src/test/java/com/wly/job/common/timewheel/TimeWheelTest.java`
- Modify: `admin/src/test/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngineTest.java`
- Modify: `common/src/main/java/com/wly/job/common/timewheel/TimeWheel.java` only if a test demonstrates a real defect.
- Modify: `admin/src/main/java/com/wly/job/server/schedule/engine/TimeWheelSchedulerEngine.java` only if a test demonstrates a real defect.

**Interfaces:** 不改变 `TimeWheel.add/remove/getAndRemove/clock` 或 `SchedulerEngine` 公共接口。

- [ ] **Step 1: 增加失败测试**
  - 跨圈同 slot：设置 60 槽，加入 10 秒和 70 秒任务，分别取 tick，断言不串任务。
  - 并发 add/remove：多线程加入互异任务并并发移除，断言无丢失/重复。
  - 真实 `clock()`：用可控逻辑时钟驱动多 tick，断言按 tick 回调。
  - 引擎 stop 后不再派发；快速 start/stop/start 后断言最多一个 `time-wheel-clock-thread`。

- [ ] **Step 2: 只修复测试暴露的生产问题**
  - 保持锁保护 `entries/taskTicks`。
  - 若发现 `clock()` 中断后 stop 状态或 restart 竞态问题，使用最小同步/状态修复，不扩展范围。

- [ ] **Step 3: 验证**
  - 运行 common 时间轮测试和 admin 引擎测试，确认无 flaky 重试。

---

### Task 4: 修复 MDC、Cleaner 和 IT 覆盖

**Files:**
- Modify: `common/src/main/java/com/wly/job/common/logging/MdcExecutorService.java` if needed
- Modify: `admin/src/main/java/com/wly/job/server/schedule/QueueReconciler.java`
- Modify: `admin/src/main/java/com/wly/job/server/schedule/ScheduleRecCleaner.java`
- Modify: `admin/src/test/java/com/wly/job/server/schedule/ScheduleRecCleanerTest.java`
- Modify: `admin/src/test/resources/application-it.yml`
- Modify: `admin/src/test/java/com/wly/job/server/service/RegisterDecouplingIT.java`
- Modify: `admin/src/test/java/com/wly/job/server/stroage/InstancePersistStorageIT.java`
- Modify: `admin/src/main/java/com/wly/job/server/stroage/RedisJobInstanceStorage.java` only if required to make storage conditional
- Modify: `admin/src/test/java/com/wly/job/server/service/RegisterDecouplingIT.java` for real concurrent insert and unique index assertion

**Interfaces:** 保持 `QueueReconciler.start/stop`、`ScheduleRecCleaner.start/stop/sweep`、IT profile 和 `JobMapper.insertIfAbsent` 语义不变。

- [ ] **Step 1: 修复 MDC scheduled 包装**
  - 为 `MdcExecutorService` 增加最小 scheduled wrapper 或为 QueueReconciler 的任务提交点使用现有 MDC decorator。
  - 保证周期任务捕获提交时 MDC，并在执行结束恢复上下文。

- [ ] **Step 2: 修复 Cleaner 可测性**
  - 修正 `BATCH_PAUSE_MS` Javadoc 为实际字段。
  - 用构造器/包内注入替代静态可变测试状态，或在测试后完整恢复原值。
  - 引入可控 scheduler/短延迟测试，既验证 start 不同步阻塞，又验证首次清扫最终发生。

- [ ] **Step 3: 隔离 IT 外部资源和数据前缀**
  - 修正 Redis profile 注释与实际 Bean 生命周期；测试不得依赖 Redis 服务。
  - 将注册 IT 与实例 IT 的 discoveryKey 前缀分开，清表只删除自身数据。

- [ ] **Step 4: 增加真实 MySQL 并发验证**
  - 两线程 CountDownLatch 同时调用相同 group/name 的 `insertIfAbsent`，断言最终一行。
  - 查询 `information_schema.STATISTICS` 断言 `uk_group_name_name` 存在。

- [ ] **Step 5: 验证**
  - 运行 Cleaner 单测、admin 单测和 `mvn -pl admin test -Dtest='*IT'`。

---

### Task 5: 全量验证与审计复审

**Files:** 不新增生产文件；根据测试失败回到对应 Task 修复。

- [ ] **Step 1:** 低内存参数运行 `mvn test`。
- [ ] **Step 2:** 运行真实 MySQL 集成测试 `mvn -pl admin test -Dtest='*IT'`。
- [ ] **Step 3:** 构建 Admin/Sample 并运行 `bash scripts/e2e-smoke-test.sh`，确认进程和端口清理安全。
- [ ] **Step 4:** 复查 `git diff HEAD` 与未跟踪文件，确认没有 P0/P1；重新执行独立审计。

---

## 计划自检

- E2E 安全、注册隔离、时间轮、MDC/IT 四条路径文件边界明确，可并行。
- 所有审计 P0/P1 均有对应任务；P2 的 Javadoc、静态状态、IT 清理范围和首次清扫也有任务。
- 没有新增运行时依赖；所有核心逻辑测试保留真实实现。
- 端到端测试仍只使用 `job_test`，且不按端口盲杀外部进程。
