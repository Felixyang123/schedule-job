# Workspace Guide for AI Agents (AGENTS.md)

本文件专为在此项目中进行协作、维护与二次开发的 AI Agent 准备。它系统性地梳理了该分布式任务调度框架的设计思想、模块职责、通信机制、核心流程以及开发约束，帮助 Agent 快速理解上下文、精准定位代码并确保修改的规范性。

---

## 1. 项目定位与核心特性

本项目是一个名为 **`job`** 的**轻量级分布式任务调度框架**（类似 XXL-JOB 但高度精简，易于扩展和集成）。

### 核心特性
1. **轻量与易用性**：提供 `job-spring-boot-starter`，通过 `@EnableScheduleJob` 和 `@ScheduleJob` 注解即可在任何标准 Spring Boot 应用中快速声明和暴露调度任务。
2. **高效通信 (Netty RPC)**：调度中心（Admin）与执行器（Client/Worker）之间使用 Netty 进行基于 TCP 协议的高性能 RPC 通信，消息编解码基于 LengthFieldFrame + 自定义 JsonCodec 实现。
3. **高内聚的调度模型**：
   - **调度中心 (Admin)** 基于 JDK `DelayQueue` 驱动的 `JobScheduler` 进行主循环调度，基于 Cron 表达式自动计算下次执行时间点并投放队列。
   - **多存储抽象**：支持集群实例的多种注册与存储方式（LocalCache、Redis、DB 存储）。
   - **灵活负载均衡**：通过 `LoadBalancer` 抽象，支持轮询 (`RoundRobinSelector`)、随机等路由策略将任务发往不同的执行器实例。
4. **自动注册与心跳续约**：执行器启动时，扫描所有标注了 `@ScheduleJob` 的方法，并将任务元数据及本实例信息注册到调度中心；利用 `DelayQueue` 在执行器本地异步周期性地向 Admin 发送心跳以维持实例活性。

---

## 2. 模块划分与核心架构

项目采用多模块 Maven 结构：

```
job (Root POM)
├── common                  # 公共模型、工具类、网络编解码与时间轮定义
├── core                    # 执行器核心引擎：Netty服务端启动、本地任务注册中心、调用钩子
├── admin                   # 调度中心服务端：提供调度核心引擎、作业/实例元数据存储、管控后台接口、路由策略
├── job-spring-boot-starter # Spring Boot 自动装配模块：扫描注解、构建核心工厂、生命周期管理
└── samples                 # 示例代码：不同注册/运行模式下的客户端与服务端示例
```

### 2.1 `common` 模块
* **`JobInfo` / `JobInstance`**：描述调度任务与物理节点的底层 DTO。
* **`JsonEncoder` / `JsonDecoder`**：Netty 通信专用的 JSON 序列化与反序列化处理器。
* **`TimeWheel`**：时间轮基础实现，支持高效的定时延时任务调度。
* **`NetworkUtils`**：自动获取物理节点服务器 IP 地址的实用工具类。

### 2.2 `core` 模块
* **`JobBootstrap`**：基于 Netty 实现的 TCP 监听服务（默认在执行器自定义端口启动，通常为 `8101` 等），用于接收 Admin 的调度命令。
* **`JobInstanceHandler`**：Netty 入站请求处理器，将 I/O 线程解码后的 `ScheduleJobRequest` 提交给独立的固定大小线程池执行，避免阻塞 Netty 线程。
* **`InnerJobRegistry` / `RemoteJobRegistry`**：
  - `InnerJobRegistry` 维护当前节点本地已加载的 `@ScheduleJob` 方法映射（`MethodInvocationJob`）。
  - `RemoteJobRegistry` 基于 HTTP 客户端（`RestClientHelper`）将任务和实例信息注册到远程 Admin 端。

### 2.3 `admin` 模块
* **`JobScheduler`**：整个系统的调度发动机。
  - 实现 `SmartLifecycle` 接口，在 Spring 容器启动时开启三个单线程线程池：
    - `asyncBuildScheduleJobs`：增量或全量地从数据库游标分批拉取任务列表，计算下次执行时间并投递至 `DelayQueue<ScheduleJob>`。
    - `asyncScheduleJobs`：不断从 `DelayQueue` 中获取到期任务，调用 `scheduleJobService.schedule()` 触发远程 RPC 调用，随后重新计算下次时间并放回队列，实现循环调度。
    - `asyncSaveScheduleRecs`：将生成或更新的调度执行记录（`ScheduleRec`）缓冲在队列中，批量持久化写入数据库。
* **`Registry` / `Storage`**：
  - 支持多层级的实例注册中心设计。
  - 通过 `Storage` 体系隔离不同的持久化策略，可选 `LocalCacheJobInstanceStorage`、`RedisJobInstanceStorage`、`Redis` 或物理 `MyBatis-Plus` 存储。
* **`LoadBalancer`**：
  - 负载均衡器。通过 `InstanceSelector` 选择具体的执行器节点。内置 `RoundRobinSelector` 等。

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

### 3.2 任务触发与调度执行流程
```
                     [ JobScheduler (DelayQueue<ScheduleJob>) ]
                                       │
                                       ▼  (take() 到期任务)
                       [ AbstractScheduleService.schedule() ]
                                       │
          ┌────────────────────────────┴────────────────────────────┐
          ▼                                                         ▼
[ Registry.discover() ]                                   [ LoadBalancer.choose() ]
(根据 DiscoveryKey 发现节点)                             (按指定路由策略选择单台 Instance)
          │                                                         │
          └────────────────────────────┬────────────────────────────┘
                                       ▼
                       [ ScheduleJobClient.send() ] -> 封包发送 Netty TCP 请求
                                       │
                                       ▼  (跨网络传输)
                        [ Netty Client SocketChannel ]
                                       │
                                       ▼
                     [ 执行器客户端 Netty Server ] -> 解码请求
                                       │
                                       ▼
                         [ JobInstanceHandler (Netty) ]
                                       │
                         (提交至 executorService 独立线程池)
                                       │
                                       ▼
                       [ MethodInvocationJob.execute() ] -> 反射调用对应 Bean 方法
                                       │
                                       ▼
                     [ Netty Channel.writeAndFlush() ] -> 返回执行结果
```

---

## 4. 核心实体定义

在与持久层和数据传输交互时，必须严格遵守以下实体和数据结构：

### 4.1 核心表实体

* **`Job` (作业元数据表)**
  - 实体路径：`com.wly.job.server.dao.entity.Job`
  - 核心字段：
    - `groupName`: 任务分组名
    - `name`: 任务唯一标识名称
    - `cron`: Cron 调度表达式
    - `status`: 状态 (0: 停止, 1: 运行)
    - `type`: 任务类型 (0: 普通任务, 1: 单次任务)
    - `strategy`: 路由策略 (0: 轮询, ...)
    - `executeParam`: 执行参数
    - `deleted`: 逻辑删除字段 (0: 正常, 1: 删除)

* **`ScheduleRec` (调度执行记录表)**
  - 实体路径：`com.wly.job.server.dao.entity.ScheduleRec`
  - 核心字段：
    - `jobId`: 作业 ID
    - `requestId` / `executionId`: 调度链路追踪 ID
    - `executeParam`: 执行参数
    - `executeResult`: 执行返回的 JSON 结果
    - `status`: 状态 (-1: 失败, 0: 运行中, 1: 成功)
    - `scheduleTime`: 调度时间
    - `completeTime`: 完成时间

---

## 5. AI Agent 开发规范与约束

任何在此项目上工作的 AI Agent，都必须绝对遵守以下规范和约束：

### 5.1 语言与技术栈约束
* **JDK 版本**：必须使用 **Java 21** 特性（如：使用 `record` 定义不可变 DTO、利用多行字符串文本、模式匹配以及局部变量类型推断 `var` 提高代码可读性）。
* **依赖引入**：尽量使用 `dependencyManagement` 中已声明的库依赖。在编写核心代码时，如无必要不随意升级 Spring Boot (3.5.6) 与 Netty (4.1.108.Final) 版本。

### 5.2 异步与线程池安全规范
* **Netty 线程保护**：在 `JobInstanceHandler` 中收到请求后，**严禁**在 Netty 的 I/O 线程（EventLoopGroup）直接进行任何耗时计算、反射调用或数据库/网络 I/O。必须将其委派给配置的客户端自定义线程池 `executorService` 执行。
* **优雅停机**：在 `smartLifecycle` 实现类（如 `JobScheduler`, `ScheduleJobAnnotationProcessor`）的 `stop()` 方法中，必须显式且温和地关闭内部线程池，设置 `running = false` 标记防止死循环，并在最大等待期限后调用 `shutdownNow()`。

### 5.3 数据库操作规范 (MyBatis-Plus)
* **逻辑删除**：`Job` 实体中配置了 `deleted` 字段作为逻辑删除字段。进行删除操作时，应使用 MyBatis-Plus 的 `removeById` 等框架自带方法，从而自动生成带有 `deleted = 1` 的 Update 语句。
* **批处理性能**：Admin 的日志记录通过 `asyncSaveScheduleRecs` 线程异步写入。由于高频调度的日志量较大，这里使用了 `saveBatch()` 攒批提交。在扩充日志逻辑时，必须保障日志落库不影响主调度循环。

### 5.4 异常处理与重试
* 自定义业务异常必须派生自 `ScheduleException` (继承 `RuntimeException`)。
* 在远程 RPC 调用或客户端反射方法时，捕获的非致命异常需妥善记录至 `ScheduleRec` 中（即更新 status 为 `FAIL` 并存储异常堆栈详情），不得向主线程外泄导致调度引擎崩溃。

---

## 6. 常见二次开发任务与修改指南

如果您需要针对某些常见任务进行扩展，请参考下表的指导方案：

| 扩展任务需求 | 涉及模块 | 关键类与路径 | 修改与扩展方案 |
| :--- | :--- | :--- | :--- |
| **新增路由算法 / 负载均衡策略** | `admin` | `com.wly.job.server.client.lb` | 1. 在 `ScheduleStrategyEnum` 中定义新的策略 Code；<br>2. 编写类实现 `InstanceSelector` 接口，定义其选择逻辑；<br>3. 在 `InstanceSelectorFactory` 中注册该选择器映射。 |
| **支持不同的注册中心 (如 Nacos)** | `admin` / `samples` | `com.wly.job.server.registry` | 1. 扩展 `RegistryTypeEnum`；<br>2. 实现 `Registry` 接口中的 `register` 与 `discover` 方法；<br>3. 在 `ScheduleConfiguration` 中根据配置切换 Bean 实例。 |
| **添加新的切面 / 拦截器** | `core` | `com.wly.job.core.invocation.InvocationHook` | 1. 实现 `InvocationHook` 接口，在执行器反射执行任务前后进行拦截处理（如链路追踪、性能监控）；<br>2. 通过 `ScheduleJobCoreFactory` 的 `invocationHooks` 注册生效。 |
| **修改默认序列化协议 (如 Protobuf)** | `common` | `com.wly.job.common.codec` | 1. 在 `common` 模块下编写对应的 `Decoder`/`Encoder` 继承自 Netty 的 `MessageToByteEncoder`/`ByteToMessageDecoder`；<br>2. 在 `JobBootstrap` (Worker) 与 `ScheduleRequestHandler` (Admin) 的 `ChannelPipeline` 中替换默认的 JsonCodec。 |

---

> 💡 **提示给 Agent**：在对调度、注册、传输等核心组件进行重构或新增逻辑前，请先仔细查阅 `samples` 目录下各 Sample 实例的实际运行模式，保证修改不破坏核心 API 的向后兼容性。
