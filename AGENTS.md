# Workspace Guide for AI Agents (AGENTS.md)

本文件专为在此项目中进行协作、维护与二次开发的 AI Agent 准备。它系统性地梳理了该分布式任务调度框架的设计思想、模块职责、通信机制、核心流程以及开发约束，帮助 Agent 快速理解上下文、精准定位代码并确保修改的规范性。

> **文档层级**：本文件是「导览 + 规范」。权威的设计决策记录在 `docs/adr/`（ADR-0001~0005）与 `docs/spec/`，术语定义见 `CONTEXT.md`，数据库初始化脚本见 `docs/sql/schema.sql`。涉及新增架构语义前，务必先阅读对应 ADR/Spec（见 §7）。

---

## 0. 快速开始（构建 / 测试 / 运行）

### 0.1 环境要求
* **JDK 21**（Spring Boot 3.5.6 父 POM 管理，`pom.xml` 中 `java.version=21`）。
* **Maven 3.9.x**。仓库自带 `mvnw` 包装器，但本机 `mvnw` 引用的 JAVA_HOME 可能指向不存在的目录；如遇 `JAVA_HOME is not defined correctly`，请将 `JAVA_HOME` 显式指向本机 JDK 21 安装目录（本机可用 `C:\Users\wangyang\.jdks\ms-21.0.10`），或直接使用系统 `mvn`。
* **MySQL 8**（必需，Admin 持久化依赖），可选 Redis（实例存储与选主实现）。

### 0.2 初始化数据库
执行 `docs/sql/schema.sql` 建表，共 6 张表：
`job_group` / `job` / `instance` / `schedule_rec` / `schedule_lock` / `job_change`。
Admin 默认数据源配置在 `admin/src/main/resources/application-dev.yml`（默认 `jdbc:mysql://localhost:3306/job`，root/lifan1994，按需修改）。

### 0.3 常用命令（项目根目录执行）
```bash
# 全量构建（含各模块安装到本地仓库）
mvn clean install -DskipTests

# 运行全部测试（common / core / admin / starter / samples）
mvn test

# 只测某个模块（如 admin）
mvn -pl admin test

# 只跑单个测试类 / 单个方法
mvn -pl admin test -Dtest=JobSchedulerTest
mvn -pl admin test -Dtest=JobSchedulerTest#maybeReconcile

# 启动 Admin 调度中心（默认端口 8100，dev profile 已默认激活）
mvn -pl admin -am spring-boot:run

# 启动 Worker 示例（job-sample，默认端口 8102，注册到 localhost:8100 的 Admin）
mvn -pl samples/job-sample -am spring-boot:run

# 启动注册中心式 Worker 示例（register-center-registry-sample）
mvn -pl samples/register-center-registry-sample -am spring-boot:run
```
> 注意：多模块 reactor 下用 `-am` 让依赖模块（common/core/starter）一并参与构建，否则单独启动 `admin` / `samples` 子模块会因依赖未安装而失败。

### 0.4 关键配置键
* **`schedule.*`（Admin 侧，`ScheduleProps`）**：`registry`（DEFAULT / 注册中心）、`service`（DEFAULT / GROUP）、`engine`（`DELAY_QUEUE` 默认 / `TIME_WHEEL`）、`dispatch-threads`（派发 worker 线程数，按 jobId 分片，默认 1）、`callback-threads`（RPC 回调线程数，默认 4，有界队列 1024）、`access-token`（开放接口与 RPC 派发鉴权 token，**未配置时 `/open/**` 一律 401**）、`rec-retention-days`（`schedule_rec` 终态记录保留天数，默认 7）、`ha.enabled`（HA 开关，默认 false）、`ha.election`（DB 默认 / REDIS）、`ha.lease-seconds`（租约，默认 10）、`ha.renew-seconds`（续约，默认 3）、`ha.poll-seconds`（选主轮询，默认 1）、`ha.stale-sweep-seconds`（陈旧 RUNNING 清扫，默认 30）。
* **`schedule-job.*`（Worker 侧，`ScheduleJobConfigProps`）**：`serverAddress`（Admin 地址，**逗号分隔多值**）、`serverSelector`（`ROUND_ROBIN` 默认 / `RANDOM` / `HASH`）、`accessToken`、`port`（Netty 监听端口，示例 8101）、`heartbeatInterval`、`http-connect-timeout`（默认 2000ms）、`http-read-timeout`（默认 3000ms，**约束：单次尝试 ≤ (租约剔除时间 − 心跳间隔) / Admin 节点数**，例 30s/10s/5 节点 → 4s）、`group.name`。
* **`management.*`（Admin 侧，可观测性）**：actuator 端点暴露 `health,info,metrics,prometheus`，指标前缀 `job.*`，见 `MetricsRegistry`。
* **日志链路（MDC `requestId`）**：`RequestLogFilter`（admin）为 `/admin/**` + `/open/**` 请求生成/透传 `X-Request-Id`（截断 64 字符，响应头回传），写入 MDC `requestId`；`MdcTaskDecorator`（common）跨线程透传 MDC 快照，已接入派发 worker / RPC 回调 / Worker 业务线程（虚拟线程兼容，见 Spec 2026-08-06 §2.3）。日志模式由各模块 `logback-spring.xml` 控制：`prod` profile 输出 JSON（`LogstashEncoder` + 异步 appender + 30 天归档），其余环境输出带 `[%X{requestId:-}]` 的文本格式。MDC key 统一 `requestId`，新跨线程点须用 `MdcTaskDecorator.decorate` 包装。

---

## 1. 项目定位与核心特性

本项目是一个名为 **`job`** 的**轻量级分布式任务调度框架**（类似 XXL-JOB 但高度精简，易于扩展和集成）。

### 核心特性
1. **轻量与易用性**：提供 `job-spring-boot-starter`，通过 `@EnableScheduleJob` 和 `@ScheduleJob` 注解即可在任何标准 Spring Boot 应用中快速声明和暴露调度任务。
2. **高效通信 (Netty RPC)**：调度中心（Admin）与执行器（Client/Worker）之间使用 Netty 进行基于 TCP 协议的高性能 RPC 通信，消息编解码基于 LengthFieldFrame + 自定义 JsonCodec 实现。
3. **高内聚的调度模型**：
   - **调度中心 (Admin)** 基于 `JobScheduler` 主循环驱动，底层调度引擎通过 `SchedulerEngine` 抽象（默认 JDK `DelayQueue`，可选 `TimeWheel`），基于 Cron 表达式自动计算下次执行时间点并投放队列。
   - **单活 HA（Single-Active）**：多 Admin 部署时通过 `LeaderElection` 选主（DB 租约锁默认 / Redis 锁可选），同一时刻仅一个节点持有调度权，其余节点待命并继续提供注册与管理接口（ADR-0004）。
   - **变更源增量对账（Change Feed）**：所有作业元数据写路径与 Job 行写入同事务地写入 `job_change` 表，主节点每秒消费做增量对账，稳态成本 ∝ 变更量而非任务总量（ADR-0005）。
   - **多存储抽象**：支持集群实例的多种注册与存储方式（LocalCache、Redis、DB 存储）。
   - **灵活负载均衡**：通过 `LoadBalancer` 抽象，支持轮询 (`RoundRobinSelector`)、随机、哈希等路由策略将任务发往不同的执行器实例。
4. **自动注册与心跳续约**：执行器启动时，扫描所有标注了 `@ScheduleJob` 的方法，并将任务元数据及本实例信息注册到调度中心；利用 `DelayQueue` 在执行器本地异步周期性地向 Admin 发送心跳以维持实例活性。
5. **单次任务可靠执行（At-Least-Once）**：单次任务派发即摘除队列并置 in-flight，执行成功后置 `finished` 终态；失败/超时/常驻清扫统一写"失败重试"变更记录重新入队（ADR-0001、ADR-0003）。

---

## 2. 模块划分与核心架构

项目采用多模块 Maven 结构：

```
job (Root POM)
├── common                  # 公共模型、工具类、网络编解码与时间轮定义
├── core                    # 执行器核心引擎：Netty服务端启动、本地任务注册中心、调用钩子、Admin 节点选择
├── admin                   # 调度中心服务端：调度引擎、选主与对账、作业/实例元数据存储、管控后台接口、路由策略
├── job-spring-boot-starter # Spring Boot 自动装配模块：扫描注解、构建核心工厂、生命周期管理
└── samples                 # 示例代码：不同注册/运行模式下的客户端与服务端示例
```

### 2.1 `common` 模块
* **`JobInfo` / `JobInstance`**：描述调度任务与物理节点的底层 DTO（表 `job` / `instance` 的对应载体）。
* **`JsonEncoder` / `JsonDecoder`**：Netty 通信专用的 JSON 序列化与反序列化处理器。
* **`ScheduleJobRequest` / `ScheduleJobResponse`**：调度 RPC 请求与执行结果响应。
* **`JobTypeEnum`**：任务类型（NORMAL / SINGLE）。
* **`TimeWheel`**：时间轮基础实现，供 `TimeWheelSchedulerEngine` 使用。
* **`NetworkUtils`**：自动获取物理节点服务器 IP 地址的实用工具类。
* **`ScheduleException`**：业务异常基类（所有自定义业务异常必须继承它）。

### 2.2 `core` 模块
* **`JobBootstrap`**：基于 Netty 实现的 TCP 监听服务（默认在执行器自定义端口启动，通常为 `8101` 等），用于接收 Admin 的调度命令。
* **`JobInstanceHandler`**：Netty 入站请求处理器，将 I/O 线程解码后的 `ScheduleJobRequest` 提交给独立的固定大小线程池执行，避免阻塞 Netty 线程。
* **`InnerJobRegistry` / `RemoteJobRegistry`**：
  - `InnerJobRegistry` 维护当前节点本地已加载的 `@ScheduleJob` 方法映射（`MethodInvocationJob`）。
  - `RemoteJobRegistry` 基于 HTTP 客户端（`RestClientHelper`）将任务和实例信息注册到远程 Admin 端。
* **`AdminNodeSelector`**：Worker 侧 Admin 节点选择抽象（`RoundRobinAdminNodeSelector` 默认 / `Random` / `Hash`），配合 `schedule-job.serverAddress` 多地址实现注册与心跳故障转移。

### 2.3 `admin` 模块
* **`JobScheduler`**（`schedule/JobScheduler.java`）：整个系统的调度发动机，实现 `SmartLifecycle` 与 `LeadershipListener`。内部线程：
  - **`job-scheduler-build`（对账线程）**：**仅主节点执行**。每秒按 id 水印消费 `job_change` 变更源（`consumeChangeFeed`，LIMIT 500，回查当前行 diff，幂等 `applyChange`）；每 60 次扫描执行一次投影全量兜底对账（`reconcileQueuedJobs`，游标 `status=1 AND finished=0`）；每 300 次扫描清理已消费变更记录。
  - **`job-scheduler-dispatch`（派发线程）**：从 `SchedulerEngine.take()` 取到期任务，按 jobId 分片（`workerIndex = jobId % threads`）投递给 worker 线程，**派发前检查 `isLeader`**（双发窗口 ≤1s）。
  - **`job-scheduler-worker-N`（执行 worker 池）**：`dispatch-threads` 个，执行 `handle()`——普通任务执行后重新计算下次时间回队（`requeue`）；单次任务先置 in-flight 再摘除队列条目，执行异常时释放 in-flight 并写 REQUEUE 变更记录。
  - 队列与 `queuedJobs` 只持轻量投影 `JobView`（id/name/cron/executeParam/strategy/type），不持有完整 `Job` 实体。
* **`schedule/engine/`（调度引擎抽象）**：`SchedulerEngine` 接口（`add` / `take` / `remove` / `clear` / `start` / `stop` / `isEmpty`），默认 `DelayQueueSchedulerEngine`，可选 `TimeWheelSchedulerEngine`；`clear()` 用于主备切换时 O(n) 清理。
* **`schedule/SingleRunTracker`**：单次任务进程内 in-flight 标记（jobId -> 派发时间），`contains` 守卫对账与回调竞态。
* **`schedule/ScheduleRunRecovery`**：陈旧 RUNNING 记录清扫（常驻，默认 30s）与接管时单次任务补触发。
* **`schedule/ScheduleRecQueue`**：ScheduleRec 异步攒批落库组件（`saveBatch()`），**独立于 `JobScheduler`** 的生命周期组件。
* **`ha/`（单活 HA）**：`LeaderElection` 接口（`isLeader` / `registerListener`），实现 `DbLeaderElection`（默认，`schedule_lock` 单行租约锁）、`RedisLeaderElection`（Lua 原子续约/释放）、`AlwaysLeaderElection`（HA 关闭时恒为主）；`ScheduleLeaderElector` 负责选主循环与监听器分发。
* **`client/`（RPC 客户端）**：`ChannelManager` 维护到各 Worker 的 Netty 连接；`ScheduleJobClient.send()` 异步派发；`client/future/ScheduleFuture` 承载异步结果；`client/callback/`（`ScheduleCallback` / `ScheduleRecCallback`）在成功/失败回调时更新 ScheduleRec、置 Finished 或写失败重试。
* **`client/lb/`（负载均衡）**：`LoadBalancer` 通过 `InstanceSelector` 选择 Worker 节点，内置 `RoundRobinSelector` / `RandomSelector` / `HashSelector`，由 `InstanceSelectorFactory` 按 `ScheduleStrategyEnum` 路由。
* **`registry/` 与 `stroage/`**：实例注册中心（`Registry`）与实例存储（`Storage`：`LocalCacheJobInstanceStorage` / `RedisJobInstanceStorage` / 物理表 `instance`）。
* **`controller/`**：管控接口 `/admin/job/*`（page / detail / edit / switch / exec / **delete**）、`/admin/group/*`、`/admin/schedule-rec/*`；开放接口 `/open/job/register`、`/open/job/instance/register`（Worker 注册与心跳）。

### 2.4 `job-spring-boot-starter` 模块
* **`ScheduleJobAutoConfiguration`**：Spring Boot 自动配置，注入 `ScheduleJobCoreFactory` 和 `ScheduleJobAnnotationProcessor`。
* **`ScheduleJobAnnotationProcessor`**：
  - 核心生命周期管理器，实现 `BeanPostProcessor` 与 `SmartLifecycle`。
  - **初始化期 (postProcessAfterInitialization)**：扫描 Spring Bean 中标注了 `@ScheduleJob` 的方法，包装为 `MethodInvocationJob` 并装载入 `InnerJobRegistry`；同时解析任务组、IP、端口等，构造 `JobInfo` 与 `JobInstance` 缓存在本地。
  - **启动期 (start)**：开启远程注册。执行 `DefaultRemoteJobRegistry.register(jobInfo)` 写入 Admin；并将 `JobInstance` 包装为周期性的 `JobInstanceRegisterTask` 投入本地的 `DelayQueue` 中，在后台线程自动执行续约心跳。

---

## 3. 核心流转流程图解

### 3.1 客户端启动、扫描与心跳注册流程
```
[ Spring Bean 实例化 ]
         │
         ▼
[ ScheduleJobAnnotationProcessor ] -> 扫描 @ScheduleJob 方法
         │
         ├──> 包装为 MethodInvocationJob 注册至本地 InnerJobRegistry
         └──> 提取 JobInfo / JobInstance 缓存到 Map
         │
         ▼ (SmartLifecycle.start())
[ 后台单线程 Executor ]
         │
         ├──> 调用 RemoteJobRegistry 向 Admin 注册作业元数据 (HTTP /open/job/register)
         └──> 将 JobInstance 包装为 JobInstanceRegisterTask 投递至 DelayQueue
                 │
                 ▼  <--- (心跳主循环)
           [DelayQueue.take()] 到期
                 │
                 ├──> 向 Admin 发送心跳包 (HTTP /open/job/instance/register)
                 └──> 设定下次心跳纳秒数，重新放回 DelayQueue
```

### 3.2 任务触发与调度执行流程（主节点）
```
[ JobScheduler.build 线程：消费 job_change 变更源 + 60s 全量兜底对账 ]
         │
         ▼  (queuedJobs.compute 幂等入队 / 变更则替换)
[ SchedulerEngine (DelayQueue<ScheduleJob> 默认) ]
         │
         ▼  (take() 到期任务, isLeader 门控)
[ JobScheduler.dispatch -> worker 线程 handle() ]
         │
         ├── 单次任务: singleRunTracker.add(in-flight) -> queuedJobs.remove
         └── 普通任务: queuedJobs.remove -> 执行完成后 requeue 回队
         │
         ▼
[ AbstractScheduleService.schedule() ]
         │
         ├──> [ Registry.discover() ]  发现候选节点
         ├──> [ LoadBalancer.choose() ] 按路由策略选择单台 Instance
         ▼
[ ScheduleJobClient.send() ] -> 封包发送 Netty TCP 请求（异步，ScheduleFuture 承载结果）
         │
         ▼  (跨网络传输)
[ Worker Netty Server ] -> 解码请求 -> [ JobInstanceHandler ]
         │
         (提交至 executorService 独立线程池)
         ▼
[ MethodInvocationJob.execute() ] -> 反射调用对应 Bean 方法
         │
         ▼
[ Netty Channel.writeAndFlush() ] -> 返回 ScheduleJobResponse
         │
         ▼  (Admin 侧回调，主节点专属)
[ ScheduleRecCallback.onSuccess/onFailure ]
         ├──> ScheduleRecQueue 攒批落库 (SUCCESS/FAIL)
         ├──> 单次成功: 置 finished=1 (type=SINGLE 守卫) -> 写"单次完成"变更记录
         └──> 单次失败/超时: 释放 in-flight -> 写"失败重试"变更记录 -> ≤1s 重新入队
```

### 3.3 变更源增量对账（ADR-0005）
```
[ 写路径 ] JobService.edit / switchStatus / delete / 注册 / 回调
   与 Job 行写入同事务 追加一条 job_change 记录 (change_type 1~6)
         │
         ▼
[ 主节点 build 线程 ] 每秒: SELECT id > watermark ORDER BY id LIMIT 500
         │
         ▼
[ applyChange(jobId) ] 回查当前行 -> queuedJobs.compute 幂等 diff
   ├── 任务不存在 / 禁用 / Finished -> 移除队列条目
   ├── 元数据变化 (cron/param/strategy/type) -> 移除旧条目 + 入队新条目
   └── 无变化 -> 保留
   └── in-flight (singleRunTracker.contains) -> 跳过整条记录
         │
         ▼ 每 60s: 投影全量兜底对账；每 5min: 清理已消费记录
         ▼ 接管成为主节点: recover() -> engine.clear() -> 全量对账 -> watermark = max(id)
```

### 3.4 单活 HA 主备切换（ADR-0004）
```
[ ScheduleLeaderElector ] 轮询 isLeader（默认 1s 刷新，租约 10s / 续约 3s）
   ├── 失去主: onLoseLeadership -> queuedJobs.clear + singleRunTracker.clear
   │            + schedulerEngine.clear()/stop + watermark=0（停止派发与对账）
   └── 成为主: onBecomeLeader -> ScheduleRunRecovery.recover()（清扫 + 补触发单次任务）
                + engine.clear()/start + 全量对账 + watermark = max(id)
```
Standby 节点：调度（对账/派发/回调）全部暂停，但 HTTP 注册与管理接口常开；HA 开启后所有任务按 **At-Least-Once** 执行，普通任务切换窗口可能重复一次（业务侧自行幂等），单次任务错过火点由接管流程补触发。

---

## 4. 核心实体定义

在与持久层和数据传输交互时，必须严格遵守以下实体和数据结构：

### 4.1 核心表实体（`docs/sql/schema.sql` 为权威建表脚本）

* **`Job` (作业元数据表)**
  - 实体路径：`com.wly.job.server.dao.entity.Job`
  - 核心字段：
    - `groupName`: 任务分组名
    - `name`: 任务唯一标识名称（`uk_group_name_name` 唯一键，逻辑删除旧行占用键名）
    - `cron`: Cron 调度表达式
    - `status`: 状态 (0: 停止, 1: 运行)
    - `type`: 任务类型 (0: 普通任务, 1: 单次任务)
    - `finished`: 单次任务业务终态标记（**不变量：finished=1 ⇒ type=1**；改回普通任务时同事务重置 0；成功回调置位带 `type=SINGLE` 守卫）
    - `strategy`: 路由策略 (1: 随机, 2: 轮询, 3: 哈希)
    - `executeParam`: 执行参数
    - `deleted`: 逻辑删除字段 (0: 正常, 1: 删除)

* **`ScheduleRec` (调度执行记录表)**
  - 实体路径：`com.wly.job.server.dao.entity.ScheduleRec`
  - 核心字段：
    - `jobId`: 作业 ID
    - `requestId` / `executionId`: 调度链路追踪 ID
    - `executeParam`: 执行参数
    - `executeResult`: 执行返回的 JSON 结果
    - `status`: 状态 (-1: 失败, 0: 运行中[RUNNING], 1: 成功)；RUNNING 是唯一非终态，超过 `reqTimeout` 宽限由主节点常驻清扫置 FAIL
    - `scheduleTime`: 调度时间
    - `completeTime`: 完成时间

* **`JobChange` (作业变更源表)**
  - 实体路径：`com.wly.job.server.dao.entity.JobChange`
  - 字段：`id`（水印）、`jobId`、`changeType`（1 注册 / 2 编辑 / 3 启停 / 4 单次完成 / 5 删除 / 6 失败重试）、`operator`、`requestId`、`jobName`、`createTime`。
  - 消费端**无视 change_type**，只回查当前行 diff，天然幂等。

* **`Instance` (执行器实例表)**
  - 实体路径：`com.wly.job.server.dao.entity.Instance`；记录 Worker 的 IP、Netty 端口、心跳活性与在线状态。

* **`ScheduleLock` (选主租约锁表)**
  - 实体路径：`com.wly.job.server.dao.mapper.ScheduleLockMapper`；单行记录持锁节点唯一 ID 与租约到期时间，供 `DbLeaderElection` 使用。

### 4.2 内存模型
* **`JobView`**（`dao/entity/JobView.java`，record）：调度队列与 `queuedJobs` 持有的**轻量投影**（id/name/cron/executeParam/strategy/type），避免在 10 万级任务下把完整 `Job` 加载进内存；调度器相关代码不得改回持有完整实体。
* **`ScheduleJob`**：`JobView` + 下次执行时间的组合，作为 `SchedulerEngine` 队列元素。

### 4.3 术语对照（权威定义见 `CONTEXT.md`）
`CONTEXT.md` 是本项目维护的术语表，与代码中的类名/表名存在映射关系，Agent 编写文档与注释时建议使用术语表词汇：
| 术语表词汇 | 代码/表名 | 说明 |
| :--- | :--- | :--- |
| Admin（调度中心） | `admin` 模块 | 服务端，多个共享 DB 的 Admin 构成集群 |
| Single-Active（单活） | `ha/` | 同一时刻仅一个 Admin 持有调度权 |
| Leader / Standby | `ha/` | 持调度权节点 / 待命节点（避免使用 master/slave、主从） |
| Job（作业） | 表 `job` | Normal Job（周期） / Single-Run Job（单次） |
| Finished（已完成） | `job.finished` | 单次任务业务终态，仅对 type=1 有效 |
| Worker（执行器） | `core` 模块、表 `instance` | 避免使用 JobInstance/client/node |
| ScheduleRecord（调度记录） | 表 `schedule_rec` | 避免使用 ScheduleRec/log/execution |
| RUNNING（执行中） | `schedule_rec.status=0` | 非终态，区别于 in-flight（队列侧进程内标记） |
| Reconcile（对账） | `JobScheduler` | 队列与持久化状态保持一致的过程 |
| Change Feed（变更源） | 表 `job_change` | 作业元数据变更的持久化消息流 |
| In-Flight（在途） | `SingleRunTracker` | 单次任务已派发等待回调的进程内标记 |

---

## 5. AI Agent 开发规范与约束

任何在此项目上工作的 AI Agent，都必须绝对遵守以下规范和约束：

### 5.1 语言与技术栈约束
* **JDK 版本**：必须使用 **Java 21** 特性（如：使用 `record` 定义不可变 DTO、利用多行字符串文本、模式匹配以及局部变量类型推断 `var` 提高代码可读性）。
* **依赖引入**：尽量使用 `dependencyManagement` 中已声明的库依赖（Spring Boot 3.5.6 / Netty 4.1.108.Final / MyBatis-Plus 3.5.7 / fastjson2 2.0.43）。在编写核心代码时，如无必要不随意升级版本，**不引入新的运行时依赖**；唯一豁免为生产可观测性所需的 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（仅 admin 模块，见 Spec 2026-08-05 §4）与日志结构化所需的 `logstash-logback-encoder:8.1`（仅 admin/core/starter，见 Spec 2026-08-06 §2.8）。

### 5.2 异步与线程池安全规范
* **Netty 线程保护**：在 `JobInstanceHandler`（Worker 侧）与 `ScheduleRequestHandler` / 回调路径（Admin 侧）收到请求后，**严禁**在 Netty 的 I/O 线程（EventLoopGroup）直接进行任何耗时计算、反射调用或数据库/网络 I/O。必须将其委派给配置的独立线程池执行。
* **优雅停机**：在 `smartLifecycle` 实现类（如 `JobScheduler`, `ScheduleRecQueue`, `ScheduleJobAnnotationProcessor`）的 `stop()` 方法中，必须显式且温和地关闭内部线程池，设置 `running = false` 标记防止死循环，并在最大等待期限后调用 `shutdownNow()`。

### 5.3 数据库操作规范 (MyBatis-Plus)
* **逻辑删除**：`Job` 实体中配置了 `deleted` 字段作为逻辑删除字段。进行删除操作时，应使用 MyBatis-Plus 的 `removeById` 等框架自带方法，从而自动生成带有 `deleted = 1` 的 Update 语句；删除语义为"不打断在途执行，Worker 重新注册不复活任务"。
* **批处理性能**：Admin 的调度记录通过 `ScheduleRecQueue` 异步攒批 `saveBatch()` 落库。在扩充日志逻辑时，必须保障日志落库不影响主调度循环。
* **事务边界**：写路径埋点（注册/编辑/启停/单次完成/删除）必须与 Job 行写入**同事务**；失败重试（REQUEUE）记录为独立插入，覆盖超时/连接断开/同步异常/常驻清扫所有释放 in-flight 的路径。

### 5.4 异常处理与重试
* 自定义业务异常必须派生自 `ScheduleException` (继承 `RuntimeException`)。
* 在远程 RPC 调用或客户端反射方法时，捕获的非致命异常需妥善记录至 `ScheduleRec` 中（即更新 status 为 `FAIL` 并存储异常堆栈详情），不得向主线程外泄导致调度引擎崩溃。

### 5.5 安全鉴权（Spec 2026-08-05 §2.1/§2.9）
* **开放接口默认拒绝**：`/open/**`（作业注册、实例心跳）由 `OpenApiTokenInterceptor` 校验 `Authorization: Bearer {token}`；`schedule.access-token` **未配置时一律返回 401**，不得放行。
* **Worker RPC 鉴权**：`ScheduleJobRequest.token` 携带 Admin 侧 `schedule.access-token`，Worker `JobInstanceHandler` 校验与本地 `schedule-job.accessToken` 一致，不匹配返回失败并断开连接；`expectedToken` 为空（未配置）时跳过校验（兼容旧部署）。
* **升级顺序**：Worker 先升级再升 Admin（旧 Admin 无 token 会被新 Worker 拒绝）。

### 5.6 调度一致性约束（新增逻辑前必读）
* **单次任务 in-flight 不变量**：`handle()` 必须**先置 in-flight 再摘除队列条目**；对账/消费路径遇 `singleRunTracker.contains(jobId)` 必须跳过（防跨主迟到 REQUEUE 并发双发）；成功回调**先置 Finished 再移除 in-flight**。
* **Finished 不变量**：`finished=1 ⇒ type=1`；`JobService.edit` 将 type 改为普通任务时同事务置 `finished=0`；成功回调置位必须加 `.eq(type, SINGLE)` 守卫。
* **释放 in-flight 的路径必须写 REQUEUE 变更记录**，不得在进程内加捷径——由主节点 ≤1s 消费重新入队。
* **HA 门控**：派发、对账、回调均为**主节点专属**逻辑，新增此类逻辑时必须先检查 `leaderElector.isLeader()`；`isLeader` 为 1s 刷新的内存标志，双发窗口 ≤1s 属已接受语义。
* **变更源消费幂等**：`applyChange` 复用 `queuedJobs.compute` 幂等入队（已有条目且元数据未变不重复 add）；消费端只以 `jobId` 回查当前行 diff，不得依赖 `change_type` 做分支。

---

## 6. 常见二次开发任务与修改指南

如果您需要针对某些常见任务进行扩展，请参考下表的指导方案：

| 扩展任务需求 | 涉及模块 | 关键类与路径 | 修改与扩展方案 |
| :--- | :--- | :--- | :--- |
| **新增路由算法 / 负载均衡策略** | `admin` | `com.wly.job.server.client.lb` | 1. 在 `ScheduleStrategyEnum` 中定义新的策略 Code；<br>2. 编写类实现 `InstanceSelector` 接口，定义其选择逻辑；<br>3. 在 `InstanceSelectorFactory` 中注册该选择器映射。 |
| **新增调度引擎** | `admin` | `com.wly.job.server.schedule.engine` | 1. 实现 `SchedulerEngine` 接口（add/take/remove/clear/start/stop/isEmpty）；<br>2. 在 `ScheduleConfiguration` / `ScheduleProps.engine` 中注册切换。 |
| **新增选主实现（如 ZK）** | `admin` | `com.wly.job.server.ha` | 1. 实现 `LeaderElection` 接口（isLeader + registerListener，注意续约/释放需原子化，参考 Redis 的 Lua 脚本）；<br>2. 在 `ScheduleConfiguration` 中按 `schedule.ha.election` 配置切换 Bean。 |
| **支持不同的注册中心 (如 Nacos)** | `admin` / `samples` | `com.wly.job.server.registry` | 1. 扩展 `RegistryTypeEnum`；<br>2. 实现 `Registry` 接口中的 `register` 与 `discover` 方法；<br>3. 在 `ScheduleConfiguration` 中根据配置切换 Bean 实例。 |
| **Worker 新增 Admin 节点选择策略** | `core` | `com.wly.job.core.selector` | 1. 实现 `AdminNodeSelector` 接口；<br>2. 在 `AdminNodeSelectorFactory` 注册映射，`schedule-job.serverSelector` 切换。 |
| **添加新的切面 / 拦截器** | `core` | `com.wly.job.core.invocation.InvocationHook` | 1. 实现 `InvocationHook` 接口，在执行器反射执行任务前后进行拦截处理（如链路追踪、性能监控）；<br>2. 通过 `ScheduleJobCoreFactory` 的 `invocationHooks` 注册生效。 |
| **修改默认序列化协议 (如 Protobuf)** | `common` | `com.wly.job.common.codec` | 1. 在 `common` 模块下编写对应的 `Decoder`/`Encoder` 继承自 Netty 的 `MessageToByteEncoder`/`ByteToMessageDecoder`；<br>2. 在 `JobBootstrap` (Worker) 与 `ScheduleRequestHandler` (Admin) 的 `ChannelPipeline` 中替换默认的 JsonCodec。 |
| **新增单次任务写路径 / 状态流转** | `admin` | `schedule/SingleRunTracker`、`client/callback/ScheduleRecCallback`、`dao/rep/JobChangeRep` | 1. 定义 `JobChangeTypeEnum` 新变更类型；<br>2. 在对应写路径同事务埋点（或独立插入 REQUEUE）；<br>3. 遵守 §5.5 in-flight / Finished 不变量。 |

---

## 7. 权威文档索引

* **`CONTEXT.md`**：术语表（Admin / Single-Active / Leader / Standby / Job / Worker / ScheduleRecord / Reconcile / Change Feed / In-Flight 等），写文档与注释前必读。
* **`docs/adr/`**：架构决策记录
  - `0001-callback-driven-single-run-jobs`：单次任务回调驱动状态机。
  - `0002-job-level-and-cluster-coordinated-round-robin`：作业级与集群协调轮询（第二阶段已被单活方案取代并关闭）。
  - `0003-single-run-jobs-at-least-once`：单次任务 At-Least-Once 语义。
  - `0004-admin-single-active-ha`：单活 HA 选主与主备切换。
  - `0005-scheduler-change-feed`：变更源增量对账与投影内存模型。
* **`docs/spec/`**：决策固化的 Spec（`2026-08-02-scheduler-refactor-spec`、`2026-08-03-admin-ha-spec`、`2026-08-04-scheduler-scalability-spec`、`2026-08-05-production-hardening-spec`、`2026-08-06-logging-hardening-spec`），含变更源消费模型、Finished 不变量、主备切换验收标准、生产加固（鉴权/超时公式/可观测性）、日志完善（补点策略/请求日志/JSON 结构化/MDC 链路/归档）。
* **`docs/sql/schema.sql`**：数据库权威建表脚本（6 张表 + 存量迁移 SQL）。

---

> 💡 **提示给 Agent**：在对调度、注册、传输、HA 等核心组件进行重构或新增逻辑前，请先仔细查阅 `samples` 目录下各 Sample 实例的实际运行模式与对应 ADR/Spec，保证修改不破坏核心 API 的向后兼容性，并遵守 §5 的调度一致性约束。
