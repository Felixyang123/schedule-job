# 作业注册与实例注册解耦 Spec（2026-08-12）

> 本 Spec 由 grill-with-docs 会话逐项决策固化而来，解决 `ScheduleJobService.registerJob` 的两条 FIXME：
> ①「注册实例（周期性事件）和 job（客户端启动时注册一次）需要独立，避免耦合」；
> ②「每次启动都会注册，服务端需要去重，但是使用数据库 dupkey 不够优雅」。
>
> 项目尚未上线，本轮接受破坏性的 RPC DTO 与接口签名变更，不保留兼容字段。

## 1. 背景与现状

### 1.1 周期性注册其实已经分离

| 环节 | 作业注册（一次性） | 实例心跳（周期性） |
|---|---|---|
| Worker 触发点 | `ScheduleJobAnnotationProcessor.java:123` | `ScheduleJobAnnotationProcessor.java:128,136,169` |
| HTTP 接口 | `POST /open/job/register` | `POST /open/job/instance/register` |
| Admin 入口 | `ScheduleJobService.registerJob(JobInfo)` | `ScheduleJobService.registerInstance(JobInstance)` |

### 1.2 真正的残留耦合

`ScheduleJobService.java:77` 在作业注册事务内额外注册了一次实例：

```java
registry.register(jobInfo.getInstance());
Job job = JobBeanConverter.convert(jobInfo).init();
```

它承担的是「首次启动保障」——避免 Worker 注册作业后、首次心跳（`heartbeatInterval` 到期）之前，Job 到期却发现无可用实例。

但代价是：
1. `JobInfo` 必须携带 `JobInstance`（`JobInfo.java:22`），而作业实体转换完全不用它（`JobBeanConverter.java:31-42`）；
2. Worker 有 N 个 `@ScheduleJob` 方法时，启动阶段会重复注册 N 次实例（DEFAULT 模式下 discoveryKey 各不相同尚可解释，GROUP 模式下 N 次注册的是同一个实例键）；
3. 作业注册接口的契约上出现了实例字段，后续极易被误用为「注册作业时顺带续约」。

### 1.3 dupkey 去重现状

`ScheduleJobService.java:79-86` 依赖唯一键异常做幂等：

```java
try {
    jobRep.save(job);
} catch (DuplicateKeyException exception) {
    return;   // 已存在，静默跳过
}
```

`docs/sql/schema.sql:36` 的 `uk_group_name_name` 是该幂等的前提，**不可删除**——两个 Worker 并发启动时，先查后插仍会同时判定「不存在」，最终只能由唯一键裁决。

## 2. 决策清单（已确认，状态：accepted）

| # | 决策点 | 结论 |
|---|------|------|
| 1 | 注册职责 | **严格拆分**：`registerJob` 不再写实例；Worker 启动时先注册实例、再注册作业 |
| 2 | `JobInfo.instance` | **删除**（破坏性 RPC DTO 变更，项目未上线，不留兼容字段） |
| 3 | Admin 节点选择键 | 两类注册**仍使用同一 `instanceKey`**（`discoveryKey:host:port`），保证 HASH 策略下同一 Worker 的作业注册与实例注册命中同一首选 Admin |
| 4 | `instanceKey` 传输方式 | **仅作为 Worker 本地方法参数**，不进入 HTTP DTO：`RemoteJobRegistry.register(JobInfo, String instanceKey)` |
| 5 | Job↔instanceKey 关联 | starter 内部 record `JobRegistration(JobInfo jobInfo, String instanceKey)`，替换 `jobInfoMap` 的值类型 |
| 6 | 实例注册去重 | Worker 启动时按 `instanceKey` 去重后注册（GROUP 模式下 N 个作业只注册 1 次实例） |
| 7 | 实例首次注册失败 | **不阻断作业注册**：沿用注册器「全部 Admin 失败仅告警」语义，由下一轮心跳自愈 |
| 8 | 重复注册去重机制 | **条件插入**（`INSERT ... SELECT ... WHERE NOT EXISTS`）；正常重复返回 0 行不走异常；并发穿透仍由唯一键异常兜底 |
| 9 | 裸 `INSERT IGNORE` | **不采用**：它会把数据截断、非法值等错误一并降级为 warning，服务端会把「字段超长被忽略」误判为「作业已存在」 |
| 10 | 已存在作业的元数据 | **完全忽略 Worker 上报值**，不同步 `cron`/`strategy`/`type`/`executeParam`：作业创建后 Admin 管理态为权威源 |
| 11 | 逻辑删除行 | `NOT EXISTS` 子查询必须覆盖 `deleted=1` 的行，Worker 重启不得复活已删除作业 |

## 3. 传输契约变更

### 3.1 `JobInfo`（common，破坏性）

```java
// 删除
private JobInstance instance;
```

删除后 `JobInfo` 只表达作业元数据：`jobname / description / cron / executeParam / strategy / type / group`。

### 3.2 `RemoteJobRegistry`（core，破坏性）

```java
public interface RemoteJobRegistry {
    /** 注册作业元数据；instanceKey 仅用于选择 Admin 节点，不进入请求体 */
    void register(JobInfo jobInfo, String instanceKey);

    /** 注册 / 续约实例心跳 */
    void register(JobInstance jobInstance);
}
```

影响实现：`DefaultRemoteJobRegistry`（core）、`RegisterCenterInstanceRegistry`（samples/register-center-registry-sample）。

### 3.3 Admin 侧入口

`ScheduleJobService.registerJob(JobInfo)` 保持签名，但：
- 删除 `jobInfo.getInstance() == null` 校验（字段已不存在）；
- 删除 `registry.register(jobInfo.getInstance())`；
- 保留 cron 校验与变更源埋点（`change_type=1`）。

`registerInstance(JobInstance)` 不变。

## 4. Worker 启动顺序

```
postProcessAfterInitialization（扫描阶段）
  ├── MethodInvocationJob → jobs
  ├── JobInstance（按模式定 discoveryKey）→ jobInstanceMap[instanceKey]
  └── JobRegistration(JobInfo, instanceKey) → jobRegistrationMap[jobName]

start()（单线程执行器内，顺序执行）
  ① 遍历 jobInstanceMap.values()：register(jobInstance)   ← 先注册实例，消除首次心跳前的派发空窗
  ② 遍历 jobs：本地注册成功后 register(jobInfo, instanceKey)
  ③ jobInstanceMap.values() → DelayQueue，进入周期心跳续约循环
```

规则：
1. 步骤 ① 按 `instanceKey` 天然去重（`jobInstanceMap` 以 instanceKey 为 key），GROUP 模式下同组 N 个作业只注册 1 次实例。
2. 步骤 ① 失败不影响步骤 ②——注册器内部已做 Admin 故障转移且不抛异常，失败仅告警，由步骤 ③ 的心跳周期自愈。
3. 步骤 ② 的 `instanceKey` 从 `JobRegistration` 取，不在启动逻辑里重新推导 discoveryKey 规则（避免规则变更时漏改）。

## 5. 条件插入去重

### 5.1 SQL

```sql
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
```

要点：
1. `NOT EXISTS` 子查询**不带 `deleted` 条件**，覆盖逻辑删除行——已删除作业不会因 Worker 重启复活（保持 ADR-0005 §4 的删除语义）。
2. MyBatis-Plus 的逻辑删除只作用于其生成的语句，本条为手写 `@Insert`，天然不受影响；但必须显式写 `deleted` 列值。
3. 返回值语义：`1` = 首次插入，`0` = 已存在。

### 5.2 调用侧行为

```java
int inserted = jobRep.getBaseMapper().insertIfAbsent(job);   // 正常路径无异常
if (inserted == 0) {
    log.debug("job already exists, register skip, job: {}", ...);
    return;
}
changeRep.record(job.getId(), REGISTER, ...);
```

并发穿透兜底：两个 Worker 同时通过 `NOT EXISTS` 时，唯一键会让其中一个抛 `DuplicateKeyException`——仍需捕获，但这是罕见竞态而非常态路径。捕获后与 `inserted == 0` 同样处理（跳过埋点）。

**不得捕获其他数据库异常**：字段超长、非法值、连接失败等必须向上抛出。

### 5.3 自增主键回填

条件插入命中时需要 `job.getId()` 写变更源。手写 `@Insert` 需显式声明：

```java
@Options(useGeneratedKeys = true, keyProperty = "id")
```

## 6. 影响清单

### Modify（common）
- `common/.../bean/JobInfo.java`：删除 `instance` 字段与相关 Javadoc。

### Modify（core）
- `core/.../registry/RemoteJobRegistry.java`：`register(JobInfo)` → `register(JobInfo, String)`。
- `core/.../registry/DefaultRemoteJobRegistry.java`：改用入参 `instanceKey`，去掉 `jobInfo.getInstance()` 判空。

### Modify（starter）
- `job-spring-boot-starter/.../processor/ScheduleJobAnnotationProcessor.java`：
  - 新增 `private record JobRegistration(JobInfo jobInfo, String instanceKey)`；
  - `jobInfoMap` → `jobRegistrationMap`（`ConcurrentMap<String, JobRegistration>`）；
  - 扫描阶段不再 `.instance(instance)`；
  - `start()` 调整为「实例 → 作业 → 心跳」三段顺序。

### Modify（admin）
- `admin/.../service/ScheduleJobService.java`：删除实例注册与 instance 判空；改用条件插入。
- `admin/.../dao/mapper/JobMapper.java`：新增 `insertIfAbsent`。
- `admin/.../dao/rep/JobRep.java`（可选）：包装方法。

### Modify（samples）
- `samples/register-center-registry-sample/.../RegisterCenterInstanceRegistry.java`：`register` 签名同步。

### Modify（测试）
- `admin/.../service/ScheduleJobServiceTest.java`：去掉 `.instance(...)`；补条件插入返回 0/1 分支与并发穿透用例。
- `core/.../registry/DefaultRemoteJobRegistryTest.java`：补作业注册用例（验证 instanceKey 参与节点选择）。

### Modify（文档）
- `AGENTS.md`：§2.2 / §2.4 注册流程、§3.1 流程图。
- `CONTEXT.md`：如涉及术语（Worker 注册 / 实例心跳）需同步。

## 7. 关键约束

- JDK 21；不引入新的运行时依赖（§5.1）。
- 变更源埋点必须与 Job 行写入同事务（§5.3）。
- 条件插入不得吞掉非唯一键的数据库异常。
- `instanceKey` 不进入任何 HTTP 请求体或 RPC DTO。
- 破坏性变更：Worker 与 Admin 必须同版本部署（`JobInfo` 结构变更，旧 Worker 发的 `instance` 字段会被新 Admin 忽略；新 Worker 不发该字段，旧 Admin 会因 instance 判空拒绝注册）。项目未上线，不提供灰度路径。

## 8. 验收标准

1. `JobInfo` 不含 `instance` 字段；全仓无 `jobInfo.getInstance()` 引用。
2. Worker 启动日志顺序为「实例注册 → 作业注册 → 心跳循环」。
3. GROUP 模式下 N 个 `@ScheduleJob` 方法只触发 1 次实例注册（不是 N 次）。
4. 实例注册全部 Admin 失败时，作业注册仍继续执行，Worker 启动不中断。
5. 重复启动 Worker：作业注册返回 0 行、无 `DuplicateKeyException`、不写 `job_change` 记录。
6. 首次启动：作业注册返回 1 行、`job.id` 被回填、写入一条 `change_type=1` 记录。
7. 字段超长等非法数据：注册抛出异常，不被静默当作「已存在」。
8. 已逻辑删除作业（`deleted=1`）对应的 Worker 重启后不复活（不产生新行、不写变更记录）。
9. HASH 选择器下，同一 Worker 的作业注册与实例注册命中同一首选 Admin 下标。
10. 全量 `mvn test` 通过。

## 9. 风险与回滚

- **破坏性 DTO 变更**：回滚需同时还原 `JobInfo.instance`、接口签名与 starter 启动逻辑，属一次性整体回滚（单个 commit）。
- **首次派发空窗**：拆分后实例注册早于作业注册，空窗比现状更小；但若步骤 ① 全失败而 ② 成功，则空窗持续到首次心跳成功（最长 `heartbeatInterval`），与现状同量级。
- **条件插入 SQL**：`FROM DUAL` 为 MySQL 语法，项目已锁定 MySQL 8（`schema.sql` 头部声明），不考虑其他方言。
- **`NOT EXISTS` 与唯一键双保险**：若未来误删唯一键，条件插入在并发下会产生重复行——唯一键为正确性底线，不可移除（`schema.sql:36`）。
