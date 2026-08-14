# 作业注册与实例注册解耦 Implementation Plan

> **For agentic workers:** 按 Task 顺序逐个实施，每个 Task 内先写测试再改实现（TDD）。Steps 使用 checkbox（`- [ ]`）跟踪。

**Goal:** 依据 `docs/spec/2026-08-12-register-decoupling-spec.md` 落地作业注册与实例注册的严格拆分，并把重复注册去重从「捕获唯一键异常」改为「条件插入」。**不改变调度语义**。

**Architecture:** ① common 删除 `JobInfo.instance`；② core 的 `RemoteJobRegistry.register(JobInfo)` 增加 `instanceKey` 参数（仅用于选 Admin 节点，不进请求体）；③ starter 用内部 record `JobRegistration` 保存 Job↔instanceKey 关联，启动顺序改为「实例 → 作业 → 心跳」；④ admin 用 `INSERT ... SELECT ... WHERE NOT EXISTS` 替代 dupkey 捕获，唯一键仅作并发兜底。

**Tech Stack:** Java 21 / Spring Boot 3.5.6 / MyBatis-Plus 3.5.7 / MySQL 8 / JUnit 5 + Mockito。

## Global Constraints

- 构建验证：`$env:JAVA_HOME = "C:\Users\wangyang\.jdks\ms-21.0.10"; $env:PATH = "$env:JAVA_HOME\bin;D:\tool\maven\bin;$env:PATH"; mvn.cmd test`（后台任务须用 `mvn.cmd`）。
- **不引入新的运行时依赖**（AGENTS.md §5.1）。
- 变更源埋点与 Job 行写入同事务（§5.3）。
- `instanceKey` 不得出现在任何 HTTP 请求体 / RPC DTO 字段中。
- 条件插入只允许捕获 `DuplicateKeyException`；其他数据库异常必须向上抛出。
- `NOT EXISTS` 子查询不带 `deleted` 条件（已删除作业不得复活）。
- 本计划在 `dev` 分支执行，不新建分支。

## File Map

- Modify: `common/src/main/java/com/wly/job/common/bean/JobInfo.java`
- Modify: `core/src/main/java/com/wly/job/core/registry/RemoteJobRegistry.java`
- Modify: `core/src/main/java/com/wly/job/core/registry/DefaultRemoteJobRegistry.java`
- Modify: `core/src/test/java/com/wly/job/core/registry/DefaultRemoteJobRegistryTest.java`
- Modify: `job-spring-boot-starter/src/main/java/com/wly/job/starter/processor/ScheduleJobAnnotationProcessor.java`
- Modify: `admin/src/main/java/com/wly/job/server/dao/mapper/JobMapper.java`
- Modify: `admin/src/main/java/com/wly/job/server/service/ScheduleJobService.java`
- Modify: `admin/src/test/java/com/wly/job/server/service/ScheduleJobServiceTest.java`
- Modify: `samples/register-center-registry-sample/src/main/java/com/wly/job/samples/center/registry/RegisterCenterInstanceRegistry.java`
- Modify: `AGENTS.md`

---

### Task 1: `JobInfo` 删除 `instance` 字段（common）

**Files:**
- Modify: `common/src/main/java/com/wly/job/common/bean/JobInfo.java`

**Interfaces:**
- Breaking: `JobInfo` 不再携带 `JobInstance`；`getInstance()` / `.instance(...)` 全部失效。

- [ ] **Step 1: 删除字段与 Javadoc 引用**

删除 `JobInfo.java:22` 的 `private JobInstance instance;` 与 `JobInstance` import；类级 Javadoc 中「其中 `instance` 携带本执行器实例信息」一句删除，改为说明作业元数据与实例心跳走两个独立接口。

- [ ] **Step 2: 确认编译失败点**

Run: `mvn.cmd -pl common install -DskipTests`
Expected: common 编译通过（`JobInfo` 无内部引用）。

Run: `mvn.cmd -pl core,admin,job-spring-boot-starter -am compile`
Expected: **编译失败**，失败点恰为 4 处：`DefaultRemoteJobRegistry:32,35`、`ScheduleJobAnnotationProcessor:88`、`ScheduleJobService:69,77`。这是 Task 2~4 的锚点，确认无遗漏引用。

---

### Task 2: `RemoteJobRegistry` 增加 `instanceKey` 参数（core + samples）

**Files:**
- Modify: `core/src/main/java/com/wly/job/core/registry/RemoteJobRegistry.java`
- Modify: `core/src/main/java/com/wly/job/core/registry/DefaultRemoteJobRegistry.java`
- Modify: `core/src/test/java/com/wly/job/core/registry/DefaultRemoteJobRegistryTest.java`
- Modify: `samples/register-center-registry-sample/src/main/java/com/wly/job/samples/center/registry/RegisterCenterInstanceRegistry.java`

**Interfaces:**
- Breaking: `void register(JobInfo jobInfo)` → `void register(JobInfo jobInfo, String instanceKey)`。

- [ ] **Step 1: 先写测试（RED）**

`DefaultRemoteJobRegistryTest` 新增：

```java
@Test
void jobRegisterUsesInstanceKeyForNodeSelection() {
    // HASH 选择器 + 固定 instanceKey：作业注册与实例注册命中同一下标
}

@Test
void jobRegisterPostsToJobRegisterPath() {
    // 验证 path 为 /open/job/register，且请求体为 JobInfo（不含 instanceKey）
}
```

用 `HashAdminNodeSelector` 构造 registry，两次调用（`register(jobInfo, key)` 与 `register(instance)` 且 `instance.getInstanceKey()` 等于 key），断言命中同一个 helper。

Run: `mvn.cmd -pl core test -Dtest=DefaultRemoteJobRegistryTest`
Expected: 编译失败（方法签名不存在）——确认测试锚定了新契约。

- [ ] **Step 2: 改接口与实现（GREEN）**

`RemoteJobRegistry`：
```java
void register(JobInfo jobInfo, String instanceKey);
void register(JobInstance jobInstance);
```
Javadoc 说明 `instanceKey` 仅用于选择 Admin 节点、不进入请求体。

`DefaultRemoteJobRegistry.register(JobInfo, String)`：
- 判空改为 `jobInfo == null` 单条件（instance 字段已不存在）；
- `postWithFailover("/open/job/register", jobInfo, instanceKey)`。

`RegisterCenterInstanceRegistry.register(JobInfo, String)`：转发 `defaultRemoteJobRegistry.register(jobInfo, instanceKey)`。

Run: `mvn.cmd -pl core test`
Expected: BUILD SUCCESS，新增用例通过。

- [ ] **Step 3: 提交**

`refactor(core): 作业注册接口显式传入 instanceKey，与实例注册共用节点选择键`

---

### Task 3: starter 启动顺序改为「实例 → 作业 → 心跳」

**Files:**
- Modify: `job-spring-boot-starter/src/main/java/com/wly/job/starter/processor/ScheduleJobAnnotationProcessor.java`

**Interfaces:**
- Internal: 新增 `private record JobRegistration(JobInfo jobInfo, String instanceKey)`；`jobInfoMap` 替换为 `jobRegistrationMap`。

- [ ] **Step 1: 引入 `JobRegistration` 并调整扫描阶段**

- 新增内部 record（放在类尾部、与 `JobInstanceRegisterTask` 同级）。
- 字段：`ConcurrentMap<String, JobInfo> jobInfoMap` → `ConcurrentMap<String, JobRegistration> jobRegistrationMap`。
- `postProcessAfterInitialization`：`JobInfo.builder()` 去掉 `.instance(instance)`；改为
  ```java
  jobRegistrationMap.putIfAbsent(scheduleJob.name(),
          new JobRegistration(jobInfo, instance.getInstanceKey()));
  ```
- `jobInstanceMap.putIfAbsent(instance.getDiscoveryKey(), instance)` 保持不变（key 即 discoveryKey，天然按实例去重）。

- [ ] **Step 2: 调整 `start()` 顺序**

在单线程执行器内改为三段：

```java
// ① 先注册实例：消除首次心跳前的派发空窗；按 instanceKey 去重，GROUP 模式只注册一次
jobInstanceMap.values().forEach(instance -> factory.getRemoteJobRegistry().register(instance));

// ② 再注册作业：实例注册失败不阻断（注册器内部已容错，仅告警）
for (InnerJob job : jobs) {
    if (factory.getInnerJobRegistry().register(job)) {
        JobRegistration registration = jobRegistrationMap.get(job.jobname());
        if (registration != null) {
            factory.getRemoteJobRegistry().register(registration.jobInfo(), registration.instanceKey());
        }
    }
}

// ③ 进入周期心跳续约
jobInstanceMap.values().forEach(jobInstance -> instanceDelayQueue.put(...));
```

注释须写明：步骤 ① 失败不阻断步骤 ②（Spec §4 规则 2）；`instanceKey` 从 `JobRegistration` 取，不重新推导 discoveryKey 规则（Spec §4 规则 3）。

- [ ] **Step 3: 验证**

Run: `mvn.cmd -pl job-spring-boot-starter -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

`refactor(starter): Worker 启动按「实例→作业→心跳」顺序，作业注册不再携带实例`

---

### Task 4: admin 侧条件插入去重

**Files:**
- Modify: `admin/src/main/java/com/wly/job/server/dao/mapper/JobMapper.java`
- Modify: `admin/src/main/java/com/wly/job/server/service/ScheduleJobService.java`
- Modify: `admin/src/test/java/com/wly/job/server/service/ScheduleJobServiceTest.java`

**Interfaces:**
- Produces: `int JobMapper.insertIfAbsent(Job job)`，返回 `1`=首次插入 / `0`=已存在。

- [ ] **Step 1: 先写测试（RED）**

`ScheduleJobServiceTest`：
- 去掉 `info()` 里的 `.instance(JobInstance.builder().build())`；
- mock `JobRep.getBaseMapper()` 返回 mock `JobMapper`；
- 用例：
  1. `insertIfAbsent` 返回 1 → 写一条 `change_type=1` 记录；
  2. `insertIfAbsent` 返回 0 → **不写**变更记录（替换原 `duplicateRegisterDoesNotRecordChange`）；
  3. `insertIfAbsent` 抛 `DuplicateKeyException`（并发穿透）→ 不写变更记录、不抛出；
  4. `insertIfAbsent` 抛 `DataIntegrityViolationException`（字段超长等）→ **向上抛出**，不被当作「已存在」；
  5. `registerJob` 不再调用 `registry.register(...)`（`verify(registry, never())`）。

Run: `mvn.cmd -pl admin test -Dtest=ScheduleJobServiceTest`
Expected: 编译失败或断言失败——确认锚定新行为。

- [ ] **Step 2: `JobMapper.insertIfAbsent`（GREEN）**

```java
@Options(useGeneratedKeys = true, keyProperty = "id")
@Insert("""
        INSERT INTO job (group_name, name, description, execute_param, cron,
                         status, type, strategy, finished, deleted,
                         create_time, update_time, creator, updater)
        SELECT #{groupName}, #{name}, #{description}, #{executeParam}, #{cron},
               #{status}, #{type}, #{strategy}, #{finished}, #{deleted},
               #{createTime}, #{updateTime}, #{creator}, #{updater}
        FROM DUAL
        WHERE NOT EXISTS (
            SELECT 1 FROM job WHERE group_name = #{groupName} AND name = #{name}
        )
        """)
int insertIfAbsent(Job job);
```

Javadoc 必须写明三点：① `NOT EXISTS` 故意不带 `deleted` 条件（已删除作业不复活，见 ADR-0005 §4）；② 返回 0 表示已存在，属正常路径；③ 唯一键 `uk_group_name_name` 仍是并发兜底，不可移除。

- [ ] **Step 3: `ScheduleJobService.registerJob` 改造**

```java
@Transactional
public void registerJob(JobInfo jobInfo) {
    if (jobInfo == null) {
        throw new ScheduleException("JobInfo must not be null");
    }
    if (!StringUtils.hasText(jobInfo.getJobname()) || !StringUtils.hasText(jobInfo.getCron())) {
        throw new ScheduleException("Job name and cron must not be blank");
    }
    CronUtils.checkCronExpression(jobInfo.getCron());

    Job job = JobBeanConverter.convert(jobInfo).init();
    int inserted;
    try {
        inserted = jobRep.getBaseMapper().insertIfAbsent(job);
    } catch (DuplicateKeyException exception) {
        // 并发穿透 NOT EXISTS 的罕见竞态：唯一键裁决，视为已存在
        log.debug("job insert lost race, register skip, job: {}", ...);
        return;
    }
    if (inserted == 0) {
        log.debug("job already exists, register skip, job: {}", ...);
        return;
    }
    changeRep.record(job.getId(), JobChangeTypeEnum.REGISTER.getCode(), "system", null, job.getName());
    log.info("job registered, job: {}", ...);
}
```

同时：删除 `registry.register(jobInfo.getInstance())`、删除 instance 判空；类级 Javadoc 移除「实例先注册到 Registry」表述，改为说明实例注册只走 `registerInstance`。

若 `registry` 字段在删除后仅被 `registerInstance` 使用，保留字段不动（仍需要）。

- [ ] **Step 4: 验证**

Run: `mvn.cmd -pl admin -am test`
Expected: BUILD SUCCESS，5 个新用例全绿。

- [ ] **Step 5: 提交**

`refactor(admin): 作业注册改条件插入去重，剥离实例注册副作用`

---

### Task 5: 文档同步与全量验证

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: 更新 `AGENTS.md`**

- §2.2 `common` 模块：`JobInfo` 描述去掉实例字段。
- §2.4 starter `启动期 (start)`：改为「① 注册实例 ② 注册作业 ③ 心跳续约」。
- §3.1 流程图：调整为实例注册先于作业注册。
- §5.3 数据库操作规范：补一条「作业注册使用条件插入（`INSERT ... WHERE NOT EXISTS`），唯一键仅作并发兜底；不得用 `INSERT IGNORE` 吞掉非唯一键错误」。
- §7 权威文档索引：新增 `docs/spec/2026-08-12-register-decoupling-spec.md`。

- [ ] **Step 2: 全量验证**

Run: `mvn.cmd test`
Expected: 9 模块 BUILD SUCCESS，`EXIT=0`，测试数不低于改造前（182）。

- [ ] **Step 3: 逐条核对验收标准**

对照 Spec §8 十条逐项确认，其中无法由单测覆盖的（第 2/3/4/8/9 条依赖真实 Worker+Admin 联调）在报告中明确标注为「需人工/集成验证」，不得声称已验证。

- [ ] **Step 4: 提交**

`docs: AGENTS.md 同步注册解耦与条件插入去重约定`

---

## Verification Checklist

- [ ] 全仓 `grep -rn "getInstance()" --include=*.java` 无 `jobInfo.getInstance()` 命中
- [ ] 全仓无 `.instance(` 构造 `JobInfo` 的调用
- [ ] `git diff -- '*pom.xml'` 为空（未引入依赖）
- [ ] `mvn.cmd test` BUILD SUCCESS + EXIT=0
- [ ] Spec §8 十条验收标准逐条有结论（单测覆盖 / 需集成验证）
