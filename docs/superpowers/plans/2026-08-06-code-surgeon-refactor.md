# 2026-08-06 代码手术师重构计划

## 背景与目标

用户选定「重构优化代码」，以代码手术师（Code Surgeon）标准审查并重构本框架。目标：

1. 清理死代码 / YAGNI 过度预留（20+ 处）
2. 收敛重复代码（构造器爆炸、重载爆炸、优雅停机模板、logback 资源冲突）
3. 拆分上帝类（JobScheduler、ScheduleRequestHandler）
4. 统一分层风格（controller 穿透）
5. 修复健壮性小缺陷（坏 cron 阻断变更源、Redis 存储 NPE、计数器无界增长）

**硬约束（不可违反，AGENTS.md §5.6）**：in-flight 不变量、Finished 不变量、变更源幂等对账、HA 门控（isLeader 1s 双发窗口）、MDC 双 key（traceId/requestId）注入矩阵、MdcExecutorService 公共 API 面（execute/submit/decorate/wrap）——以上语义全部保持，仅做结构拆分与错误隔离。

## 范围决策记录

- 模式：**全自动模式**（用户选择"全自动范围"），决策记录标注采用代码手术师推荐方案。
- 高风险项（JobScheduler 拆分）分步走，每步执行后跑测试验证再推进。

## 分阶段执行计划

### 阶段 1：死代码清理（低风险，纯删除）

| # | 位置 | 操作 |
|---|------|------|
| 1 | `admin/registry/Storage.java` 及 4 实现 | 接口删 `get`/`add`/`addAll`（无调用方，实现均 UnsupportedOperationException） |
| 2 | `admin/registry/Registry.java` 及 2 实现 | 接口删 `unregister`/`batchRegister`（无调用方；两个 impl 的 batchRegister 逐字重复） |
| 3 | `admin/utils/CronUtils` | 删 `getNextExecutionSecond/Millis/getNextExecutions`（仅 getNextExecutionNanos 被用） |
| 4 | `admin/metrics/MetricsRegistry` | 删 `JOB_HEARTBEAT_FAILURE` 预留常量 |
| 5 | `admin/client/lb/InstanceSelectorFactory` | 删 `addSelector`（无调用，选择器靠 Spring 收集） |
| 6 | `admin/.../ScheduleRequestHandler` | 删 `get`/`isTimeout`/`getPendingRequestCount`/2×getSnapshot 共 5 个死方法 |
| 7 | `admin/client/future/ScheduleFuture` | 删 `isTimeout`/`setTimeout`（保留 `get()`/`get(timeout)` 供测试） |
| 8 | `admin/enumeration/RegistryTypeEnum` | 删整个文件；修 `ScheduleProps` Javadoc @see 引用 |
| 9 | `core/ScheduleJobCoreFactory` | 删 2 个无调用构造器（8 参单地址 / 12 参带 RestClientHelper）；删 `restClientHelper` 参数/字段/`getRestClientHelper()`（全仓库无调用） |
| 10 | `core/common/ReflectionParameterConverter` | 删 `convertParameters(Method, String[])` 死方法 |
| 11 | `core/bean/ExecuteJobContext` | 删 `RESPONSE` ThreadLocal + `setResponse/getResponse`（保留 `getExecuteParam` 对外 API） |
| 12 | `core/helper/RestClientHelper` | 收敛重载：仅保留真实使用的 `post(url, body, ParameterizedTypeReference)` 及必要变体（get/put/delete/exchange 全删） |
| 13 | `samples/admin-registry-sample/.../CommonConfiguration` | 删空配置类 |
| 14 | `common/logging/MdcExecutorService` | 删未使用的 `import org.slf4j.MDC` / `java.util.Map` |
| 15 | `common/timewheel/TimeWheel.Entry` | public record → 包级（无外部引用） |
| 16 | `starter/ScheduleJobAnnotationProcessor` | `JobInstanceRegisterTask` public → private |
| 17 | `common/bean/ScheduleJobResponse` | 去 `implements Serializable` + serialVersionUID（自定义 JSON codec 传输，无必要） |

### 阶段 2：重复收敛（中低风险）

| # | 位置 | 操作 |
|---|------|------|
| 18 | 优雅停机模板 7 处（JobScheduler/ScheduleRecQueue/ScheduleRecCleaner/ScheduleRunRecovery/NettyLifecycle/LocalCache/Redis） | 抽取公共工具 `shutdownGracefully(ExecutorService, timeout)`（common 模块），保持 daemon + running 标志 + 2s + 中断复位语义（AGENTS.md §5.2 约束） |
| 19 | `core/logback-spring.xml` 与 `starter/logback-spring.xml` 逐字节相同 | 删除 starter 副本（starter 依赖 core，收敛到 core 一处，消除类路径资源冲突隐患） |
| 20 | `admin/service/DefaultScheduleServiceImpl` + `GroupNameDiscoveryScheduleService` + `AbstractScheduleService` | 2 子类仅 discoveryKey 一行差异 → 合并为单类，key 提取函数注入（策略→函数） |
| 21 | `starter/ScheduleJobAnnotationProcessor:69,122` | `getHeartbeatInterval() * 3000L` 魔法数抽共享常量 |
| 22 | `core/ReflectionParameterConverter.convertToSimpleType` | 补 short/byte/char 分支，与 `getDefaultValue` 对齐（O1） |

### 阶段 3：健壮性修复（中风险）

| # | 位置 | 操作 |
|---|------|------|
| 23 | `admin/schedule/JobScheduler.applyChange:213-245` / `reconcileQueuedJobs:260-306` | 单条记录 try/catch 隔离：坏 cron/脏数据不中断整批变更源消费与全量对账，错误记录日志后继续（水印不停滞）。**不改对账语义** |
| 24 | `admin/stroage/RedisJobInstanceStorage.calculateTimeout:68-70` | expireTime 为 null 时按已过期处理，与 LocalCache/`JobInstance.isExpired` 行为对齐（F3） |
| 25 | `admin/client/lb/RoundRobinSelector:22,30` | 计数器无界增长 → 清理空 discoveryKey 条目（F4） |
| 26 | `admin/registry/RefreshStorage` | 接口 default `start()` 内嵌线程生命周期（职责错位 + 无 try/catch 线程死亡隐患）→ 线程管理下沉到实现类 `RefreshJobInstanceStorage`（C1） |

### 阶段 4：结构拆分（高风险，逐步验证）

| # | 位置 | 操作 |
|---|------|------|
| 27 | `admin/.../ScheduleRequestHandler` | 阶段 1 已删死方法，此处审视三 map 结构是否可内聚（视代码现状决定，不强拆） |
| 28 | controller 分层统一（JobController/GroupController/ScheduleRecController） | 消除穿透 service 暴露 Rep、绕过 service 直连仓储三种风格；**先补 controller 测试**再改（E1/E2） |
| 29 | `admin/schedule/JobScheduler`（495 行） | 拆「对账器」（变更源消费+全量对账+recover）与「派发执行器」（dispatch+worker+handle+requeue），保留 JobScheduler 编排；**最后做**，以 JobSchedulerTest/ScheduleRunRecoveryTest 为回归基线，每步验证 |

### 阶段 5：文档对齐与收尾

| # | 位置 | 操作 |
|---|------|------|
| 30 | `AGENTS.md §4.1` | ScheduleRec 字段文档漂移（声称 requestId/executionId 双字段，实体仅 requestId）→ 修正文档（G2） |
| 31 | 全量构建 + 测试 | `mvn clean install -DskipTests` + `mvn test`（125 用例基线），提交 |

## 验收标准

1. 全部死代码删除项均有 grep 证据无调用方（删除前验证）。
2. 全部不变量（in-flight/Finished/变更源/HA/MDC 双 key/MdcExecutorService API）测试通过，无行为变化。
3. 全量测试 ≥ 基线（125），新增覆盖 controller 层（此前无测试）。
4. `mvn clean install -DskipTests` 全模块构建成功。
5. 审计无 P0/P1 遗留。

## 明确不做（deferred）

- `stroage` 包名拼写重命名（G1，影响面大收益低）
- `TimeWheelSchedulerEngine.isEmpty/remove` 语义修正（F2，TIME_WHEEL 非默认路径，风险高收益低）
- `JobInstanceHandler` 线程池可配置化（O2，新增配置面，属特性非清理）
- samples 示例任务去重（DRY4，样例零差异可接受）
- `MdcTaskDecorator.wrap` 与 `MdcExecutorService.wrap` 重复（DRY5，受约束不可改）
- `ScheduleRunRecovery` 与 JobScheduler 职责重叠深度重构（D2，牵动调度核心）
- `JobBeanConverter` 8 重载拆分（C4，低价值）
- samples O3 group null 守卫（样例代码）
