# 日志完善（Logging Hardening）Spec（2026-08-06）

> 本 Spec 由 grill-with-docs review 会话逐项决策固化而来。目标：提升日志可观测性——全链路 requestId 串联、结构化 JSON 日志（prod）、统一 HTTP 请求日志、关键路径日志补点、文件归档与异步化。实施计划另起一轮（`docs/superpowers/plans/2026-08-06-logging-hardening.md`），本轮只固化决策。

## 1. 背景与目标

现状：日志覆盖稀疏（error 26 / info 19 / warn 14 / debug 9），无自定义 logback 配置（纯控制台），无 HTTP 请求日志，调度链路 `requestId` 未注入日志上下文，无法跨线程串联一次调度的完整日志；prod 无文件输出、无归档、无结构化日志。

目标：
1. **全链路串联**：一次调度（HTTP 入口 → 派发 → Worker 执行 → 回调）的日志通过 `requestId` 可一键 grep 检索。
2. **结构化输出**：prod 输出 JSON 日志（ELK/Loki 可采集），dev 保持人类可读文本。
3. **故障可定位**：关键路径补齐日志，正常态安静、异常态醒目。
4. **落盘归档**：文件输出 + 按天滚动 + 30 天保留 + 异步化，日志 IO 不阻塞调度主链路。

**不改变调度语义**：日志改造纯可观测性，At-Least-Once、in-flight/Finished 不变量、变更源增量对账、单活 HA 均保持现状。

## 2. 决策清单（已确认，状态：accepted）

### 2.1 范围（#1）

A~E 全部落地：
- **A** 日志补点（关键路径覆盖）
- **B** HTTP 请求日志（统一请求日志）
- **C** 结构化 JSON 日志（prod）
- **D** MDC 链路追踪（requestId 注入）
- **E** 文件输出 + 归档 + 异步

### 2.2 JSON 环境策略与依赖豁免（#2）

| 项 | 决策 |
|---|------|
| JSON 输出环境 | **仅 prod** 输出 JSON；dev 保持人类可读文本（彩色可选）。一套 `logback-spring.xml` 内用 `<springProfile name="prod">` 切换 |
| 依赖豁免 | `logstash-logback-encoder`（版本 8.1，兼容 logback 1.5.x）引入 admin/core/starter 三模块；**扩展 AGENTS.md §5.1 依赖豁免**：由"仅 actuator/prometheus"扩为"actuator/prometheus + logstash-logback-encoder" |

### 2.3 MDC 链路追踪（#3/#4/#5）

| 项 | 决策 |
|---|------|
| **MDC key（双 key）** | **`traceId`**（链路追踪 ID，贯穿整个请求/调度链路**不变**）+ **`requestId`**（调度执行 ID，每次调度**唯一**）。日志按 `traceId` 聚合一次操作、按 `requestId` 定位单次调度执行；`schedule_rec.requestId` 恒用 R2 |
| **ID 身份** | **R1 = traceId**：HTTP 层 `X-Request-Id`（外部可注入、不可控），身份意义，贯穿请求→调度→Worker→回调；**R2 = requestId**：内部生成、不外泄，每次调度执行独立，自闭环处理（`schedule_rec`/`job_change` 关联） |
| **入口注入矩阵** | ① HTTP：`RequestLogFilter` 注入 `traceId`（R1），响应头回传 `X-Request-Id`；② cron 派发：`JobScheduler` 派发线程注入 `traceId=requestId`（链路起点，无 HTTP 上下文）；③ 手动 exec：`JobService.exec` 注入 `requestId`（R2），`traceId` 保留 R1；④ 补触发：`ScheduleRunRecovery` 注入 `traceId=requestId`；⑤ Worker：`JobInstanceHandler` 从 `ScheduleJobRequest` 取双值注入；⑥ 回调：`ScheduleFuture` 从请求对象取双值注入 |
| **透传** | `ScheduleJobRequest` 增加 `traceId` 字段，Admin 派发时携带 MDC traceId，Worker 执行与回调日志据此聚合（同一请求多段日志 traceId 一致） |
| **中途只读** | `ScheduleJobService.schedule()` **只从 MDC 读 `requestId`**（不注入），空则生成仅用于 schedule_rec（不注入 MDC）；业务同步代码零 MDC 操作 |
| **跨线程传递** | **`MdcExecutorService`**（common，`wrap(ExecutorService)`）：`execute/submit/invokeAll` 自动 `decorate` 透传 MDC 快照，业务提交点零包装；`MdcTaskDecorator` 保留 `decorate` 实现并委托 `wrap` |
| 工具归属 | common 模块 `com.wly.job.common.logging.MdcExecutorService` / `MdcTaskDecorator`（纯 JDK + slf4j，无 Spring 依赖） |
| 包装点 | ① `JobScheduler` build/dispatch/workers ② `ScheduleFuture` 回调池 ③ `JobInstanceHandler` 业务池 ④ Worker 心跳/注册循环——均经 `MdcExecutorService.wrap` |
| **虚拟线程兼容** | `MdcExecutorService` 与虚拟线程化（未来项）兼容：虚拟线程间无自动 MDC 传播，包装仍必要；"恢复快照"策略在平台线程池与虚拟线程下均正确；未来仅需换线程工厂，包装原位复用。Javadoc 明确记录此兼容性 |

### 2.4 HTTP 请求日志（#6）

| 项 | 决策 |
|---|------|
| 载体 | `RequestLogFilter`（`jakarta.servlet.Filter`，注册 `/**`），注入 `traceId`（R1）+ 请求日志 |
| 范围 | `/admin/**` + `/open/**`；**排除 `/actuator/**`**（健康检查高频噪音） |
| body | **不记录请求/响应 body**（敏感数据 + 日志膨胀）；必要时经 `schedule_rec.execute_result` 追执行结果 |
| 日志内容 | `method url status 耗时 traceId clientIp` |

### 2.5 关键路径日志补点（#7）

**策略：成功 debug / 失败 warn（二分）。** 正常运行时 info 安静（仅系统事件），异常时 warn/error 醒目。

| 位置 | 级别 | 内容 |
|---|------|------|
| `JobScheduler.handle` 派发成功 | debug | `jobId name requestId -> instance:port` |
| `ScheduleRecCallback.onSuccess` | debug | `requestId jobId -> SUCCESS` |
| `ScheduleRecCallback.onFailure` | warn | `requestId jobId -> FAIL` + 异常（低频、故障定位价值最高） |
| `ScheduleRecCallback` 置 Finished 失败（update 影响 0 行） | **warn** | 静默失败风险点：type=SINGLE 守卫命中 0 行（任务被改类型/重复完成） |
| `AbstractScheduleService` 候选数 | debug | `job -> candidates=N`；无候选 warn（已有） |
| `JobScheduler.applyChange` | debug | `jobId changeType -> add/remove/replace/skip` |
| `ScheduleJobService.registerJob` 成功 | info | `group:name` 注册成功；DuplicateKey 静默跳过改 debug |
| `DefaultRemoteJobRegistry.postWithFailover` 成功 | debug | `path key -> adminAddr OK` |
| `ScheduleRunRecovery` 清扫结果 | info | `released=N staleRunning=N` |
| `ScheduleRecQueue` 每批落库 | debug | 条数 |

### 2.6 JSON schema（#9）

`LogstashEncoder` 默认字段顺序（timestamp/version/level/logger/thread/message）+ MDC 字段：

| 字段 | 来源 |
|---|------|
| `@timestamp` | encoder 内置 |
| `level` / `level_value` | 内置（8.1 默认输出） |
| `logger` / `thread` / `message` | 内置 |
| `traceId` | MDC（链路追踪 ID） |
| `requestId` | MDC（调度执行 ID） |
| `stack_trace` | 异常（完整堆栈） |

dev 文本 pattern（带双 key 占位）：
```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-}][%X{requestId:-}] %logger{36} - %msg%n
```

### 2.7 文件归档（#8）

| 项 | 决策 |
|---|------|
| 路径 | `logs/{appname}/{appname}-{yyyy-MM-dd}.log`（按天） |
| 滚动 | `TimeBasedRollingPolicy` + 归档目录 `logs/{appname}/archive/` |
| 保留 | `maxHistory=30` 天 + `totalSizeCap=10GB` |
| 异步 | `AsyncAppender`（`AsyncQueueCapacity=8192`，`neverBlock=true` 或明确丢弃策略）——日志 IO 不阻塞派发/回调线程（同"落库不得影响主调度循环"理念） |

### 2.8 依赖与配置组织（#10）

| 项 | 决策 |
|---|------|
| 版本 | `logstash-logback-encoder` 8.1（兼容 logback 1.5.x / Spring Boot 3.5.6） |
| 作用范围 | admin / core / job-spring-boot-starter 三模块 |
| 配置组织 | 每模块 `src/main/resources/logback-spring.xml`，`<springProfile name="prod">` 切 JSON/文本；**不开放**每模块个性化，统一继承 |
| 豁免更新 | AGENTS.md §5.1 依赖豁免扩展 |

## 3. 依赖变更

| 模块 | 变更 |
|---|---|
| `admin` / `core` / `job-spring-boot-starter` | 新增 `net.logstash.logback:logstash-logback-encoder:8.1`（version 显式指定，非 BOM 管理） |

## 4. 关键约束

- JDK 21；Spring Boot 3.5.6；logback 1.5.x（BOM 管理）。
- **不改变调度语义**：日志改造纯可观测性。
- 日志 IO 不得阻塞调度主链路（异步 appender）。
- MDC key 统一 `requestId`；`MdcTaskDecorator` 记录虚拟线程兼容性。
- 成功类高频日志 debug，失败类低频 warn/error。
- prod JSON / dev 文本，单文件 `<springProfile>` 切换。
- 新增依赖豁免仅限 `logstash-logback-encoder`，其余不得新增。

## 5. 验收标准（实施轮）

1. **traceId/requestId 双 key（贯穿 + 唯一）**：调度链路（派发 → Worker 执行 → 回调）全程日志含同一 `traceId`（贯穿不变）与同一 `requestId`（调度层 R2，与 `schedule_rec` 一致）；手动 exec 链路 `traceId` 恒为 HTTP 层 R1（`X-Request-Id` 响应头回传），cron/补触发链路 `traceId=requestId`；一次请求链路中 `traceId` 不中途变化。
2. **HTTP 请求日志**：`/admin/**`、`/open/**` 有 `method url status 耗时 traceId clientIp` 日志；`/actuator/**` 无请求日志。
3. **JSON 输出（prod）**：`/actuator/health` 不受影响；prod profile 下日志为 JSON，含 `@timestamp`/`level`/`level_value`/`logger`/`thread`/`message`/`traceId`/`requestId`；异常含完整 `stack_trace`。
4. **dev 文本**：dev profile 下人类可读，含 `[requestId]` 占位。
5. **日志补点**：P0 路径（handle 派发/回调成功/失败/置 Finished 失败）日志级别与内容符合 2.5。
6. **文件归档**：`logs/{appname}/` 生成按天文件；滚动、30 天保留、10GB cap 生效；异步 appender 启用且日志 IO 不阻塞调度（派发延迟指标无异常）。
7. **虚拟线程兼容说明**：`MdcTaskDecorator` Javadoc 含虚拟线程兼容性说明。
8. 全量 `mvn test` 通过。

## 6. 风险与回滚

- **异步 appender 丢日志**：`neverBlock` 下队列满丢弃——接受（监控 `discardedCount`，可调 `AsyncQueueCapacity`）。
- **JSON 字段变更影响采集**：schema 固化后变更需同步采集端（ELK 索引模板）。
- **requestId 头注入**：外部传恶意超长 `X-Request-Id`——`RequestLogFilter` 需截断（如 64 字符）。
- **依赖冲突**：logstash-logback-encoder 8.1 与 logback 1.5 兼容性已验证；回滚移除依赖 + 删除 logback-spring.xml 即可。

## 7. 未来项

- **虚拟线程化**：线程工厂切换后 `MdcTaskDecorator` 原位复用（2.3 已固化兼容性）。
- **日志采集对接**：ELK/Loki pipeline、索引模板。
- **ScopedValue 演进**：JDK 正式化后经 `MdcTaskDecorator` 单一入口集中替换。
- **敏感信息脱敏**：若未来需记录 body，先建脱敏规则。
