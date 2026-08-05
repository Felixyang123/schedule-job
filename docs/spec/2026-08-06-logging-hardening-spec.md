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
| MDC key | `requestId`（与既有概念/字段/表列一致） |
| HTTP 透传 | `RequestLogFilter`（合并 requestId 生成与请求日志）：读 `X-Request-Id` 头（外部可注入），缺失则生成 UUID；写入 MDC；响应头 `X-Request-Id` 回传；finally 清 MDC |
| **分层模型** | **requestId 分两层，相互独立**：① HTTP 层（R1）——`RequestLogFilter` 由 `X-Request-Id` 生成/透传，用于 HTTP 请求日志；② 调度层（R2）——`ScheduleJobService.schedule()` 生成的 UUID，与 `schedule_rec.requestId` 一致，用于调度链路（派发→Worker→回调）日志。`schedule()` 用 R2 覆盖 MDC（使调度段日志关联 schedule_rec），返回前 finally **恢复 R1**（非盲 remove），不破坏 HTTP 层链路。**两层不可合并**：`ScheduleRecQueue` 按 requestId 回写 schedule_rec，合并会在 REQUEUE 重派发/外部重复请求头时造成多行误更新 |
| 跨线程传递 | **`MdcTaskDecorator`**（自定义工具，非 TTL 依赖）：捕获父线程 MDC 快照 → 任务执行前恢复 → `finally` 恢复快照（非 clear，保证复用线程不污染） |
| 工具归属 | common 模块 `com.wly.job.common.logging.MdcTaskDecorator`（纯 JDK + slf4j，无 Spring 依赖） |
| 包装点 | ① `JobScheduler` worker 提交 ② `ScheduleFuture` 回调提交 ③ `JobInstanceHandler` 业务线程池 ④ Worker 心跳/注册循环。后台常驻线程（ScheduleRecQueue/ScheduleRunRecovery）不包装（无 requestId 上下文，透传空快照无收益） |
| **虚拟线程兼容** | `MdcTaskDecorator` 与虚拟线程化（未来项）兼容：虚拟线程间无自动 MDC 传播，装饰器仍必要；"恢复快照"策略在平台线程池与虚拟线程下均正确；未来仅需换线程工厂，装饰器原位复用。Javadoc 明确记录此兼容性 |

### 2.4 HTTP 请求日志（#6）

| 项 | 决策 |
|---|------|
| 载体 | `RequestLogFilter`（`jakarta.servlet.Filter`，注册 `/**`），合并 requestId 生成 + 请求日志 |
| 范围 | `/admin/**` + `/open/**`；**排除 `/actuator/**`**（健康检查高频噪音） |
| body | **不记录请求/响应 body**（敏感数据 + 日志膨胀）；必要时经 `schedule_rec.execute_result` 追执行结果 |
| 日志内容 | `method url status 耗时 requestId clientIp` |

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
| `level` / `level_value` | 内置（`includeLevelValue=true`） |
| `logger` / `thread` / `message` | 内置 |
| `requestId` | MDC |
| `stack_trace` | 异常（完整堆栈） |

dev 文本 pattern（带 requestId 占位）：
```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{requestId:-}] %logger{36} - %msg%n
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

1. **requestId 串联（分层）**：调度链路（派发 → Worker 执行 → 回调）全程日志含同一 `requestId`（调度层 R2，与 `schedule_rec` 一致）；HTTP 层（R1，`X-Request-Id`）在响应头回传，且 `schedule()` 返回后恢复 R1 不破坏 HTTP 层后续日志（见 2.3 分层模型）。
2. **HTTP 请求日志**：`/admin/**`、`/open/**` 有 `method url status 耗时 requestId clientIp` 日志；`/actuator/**` 无请求日志。
3. **JSON 输出（prod）**：`/actuator/health` 不受影响；prod profile 下日志为 JSON，含 `@timestamp`/`level`/`level_value`/`logger`/`thread`/`message`/`requestId`；异常含完整 `stack_trace`。
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
