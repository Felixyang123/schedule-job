# MDC 双 Key（traceId / requestId）使用规范

> 面向在此项目（`job` 分布式任务调度框架）开发与二次开发的开发者与 AI Agent。
> 本文是**可执行约定**：新增代码涉及日志链路、跨线程、跨服务时，必须遵守本文规则。
> 权威决策见 `docs/spec/2026-08-06-logging-hardening-spec.md` §2.3；术语见 `CONTEXT.md`。

## 1. 双 Key 语义与身份

| Key | 名称 | 语义 | 生成者 | 不变性 |
| :--- | :--- | :--- | :--- | :--- |
| `traceId` | 链路追踪 ID（R1） | 标识一次完整请求/调度的链路，**贯穿请求 → 调度 → Worker → 回调全程不变** | HTTP 入口 `X-Request-Id`（外部可传）；cron/补触发链路起点 | **一旦注入不得改写** |
| `requestId` | 调度执行 ID（R2） | 标识**单次调度执行**，每次唯一，与 `schedule_rec.requestId` 一致 | 调度任务入口生成 | 每次调度唯一；**不在服务边界重写** |

**为什么必须分开**：R1 外部可注入、身份意义（回答"这次操作是谁"），R2 内部生成、自闭环（回答"这次执行属于哪条记录"）。共用同一个 MDC key 会在调度入口用 R2 覆盖 R1，导致一次请求链路中日志 traceId 中途变化——**违反链路追踪语义，禁止**。

## 2. 生命周期与入口注入矩阵

requestId 与 traceId 的注入**只发生在任务/流量入口**；业务同步代码**只读不注入**。

| 入口 | 线程 | 注入 traceId | 注入 requestId | 清理 |
| :--- | :--- | :--- | :--- | :--- |
| HTTP 请求 | HTTP 线程 | R1（`X-Request-Id`，缺失生成） | 无 | `RequestLogFilter` finally |
| cron 派发 | dispatch 线程 | = R2（链路起点） | R2（生成） | 派发循环 finally |
| 手动 exec | HTTP 线程 | 保留 R1（不覆盖） | R2（`JobService.exec` 注入） | exec finally |
| 接管补触发 | 清扫线程 | = R2（链路起点） | R2（生成） | `dispatchCatchUp` finally |
| Worker 执行 | Netty → 业务池 | 从 `ScheduleJobRequest.traceId` 取 | 从 `ScheduleJobRequest.requestId` 取 | `JobInstanceHandler` finally |
| RPC 回调 | 回调线程池 | 从请求对象取 | 从请求对象取 | `ScheduleFuture` finally |

**规则**：
1. **traceId 贯穿**：一旦注入，整条链路所有日志必须携带同一 traceId，禁止中途修改或删除。
2. **requestId 唯一**：每次调度执行独立生成，与 `schedule_rec` 关联，禁止复用。
3. **手动 exec 注入 requestId 时不得覆盖 traceId**（traceId 已由 `RequestLogFilter` 注入 R1）。
4. **中途只读**：`ScheduleJobService.schedule()` 等业务方法只 `MDC.get("requestId")`，不 put 不 remove（兜底生成仅用于 schedule_rec，不注入 MDC）。

## 3. 跨线程透传

`ThreadLocal`（MDC 底层）不跨线程传播，所有跨线程提交必须经过增强线程池。

**硬性规则**：
- 新线程池创建一律 `MdcExecutorService.wrap(executor)`（common 模块），`execute/submit/invokeAll` 自动透传 MDC 快照。
- **禁止**在调用点手动 `MdcTaskDecorator.decorate(task)` 包装——透传是线程池的职责，业务提交点零包装。
- 后台常驻线程（对账、清扫、心跳、落库消费）无业务链路 ID：经 wrap 透传空快照即可，不强制注入。

**虚拟线程兼容**：`MdcExecutorService` 在虚拟线程化后原位复用（虚拟线程间同样无自动 MDC 传播）；未来仅需切换线程工厂。

## 4. 新增代码硬性规则（必读清单）

新增或修改代码时逐条核对：

- [ ] 新线程池是否 `MdcExecutorService.wrap`？
- [ ] 业务同步代码是否只读 MDC、未注入/未删除？
- [ ] 新增跨线程点是否从**请求对象**取 traceId/requestId（而非假定当前线程 MDC 有值）？
- [ ] 是否覆盖了 traceId？（禁止——traceId 一旦注入不得修改）
- [ ] 新增 RPC 消息（如 `ScheduleJobRequest` 的扩展）是否携带 `traceId` 字段供下游透传？
- [ ] 日志是否统一 `[traceId][requestId]` 语义（dev 文本 `[%X{traceId:-}][%X{requestId:-}]`；prod JSON 两字段）？
- [ ] 成功类高频日志 debug、失败类低频日志 warn/error（二分策略）？

## 5. 反模式清单（禁止）

| 反模式 | 后果 |
| :--- | :--- |
| 业务方法内 `MDC.put("traceId", ...)` 覆盖 | 链路 traceId 中途变化，无法按 traceId 聚合 |
| 业务方法内 `MDC.put/remove("requestId")` | 注入职责错位；调度入口生成的 R2 被覆盖或清理 |
| 共用 MDC key 承载 R1 与 R2 | 手动 exec 路径 traceId 被 R2 顶替（历史缺陷，已修复） |
| 调用点手动 `decorate` | 透传职责散落，未来切虚拟线程需逐个改 |
| 跨线程处从"当前线程 MDC"假定取 requestId | Worker/回调线程 MDC 为空，链路 ID 丢失 |
| 服务边界重写 requestId | `schedule_rec` 关联断裂；重复请求头致多行误更新 |

## 6. 跨服务调用透传

跨服务边界上，**业务链路一律沿用上游 traceId，不得改写；requestId 只透传不重写**。

| 场景 | traceId | requestId | 约定 |
| :--- | :--- | :--- | :--- |
| 外部系统 → Admin（HTTP） | 沿用 `X-Request-Id`（缺失生成） | 无 | 业务链路入口；响应头回传 traceId |
| Admin → Worker（RPC 派发） | **沿用**（`ScheduleJobRequest.traceId`） | 透传（不修改） | 下游不得改写 traceId |
| Worker → Admin（心跳/注册） | 新生成 | 无 | **系统行为，链路起点**（非业务链路） |
| Worker → Admin（回调） | 沿用（请求对象携带） | 透传 | 归属原调度链路 |
| Admin 内部（cron/补触发） | 新生成 | = traceId | 无上游上下文，链路起点 |
| 未来注册中心/外部 SDK | 沿用传入 traceId | 无 | 跨服务边界一律沿用 |

**核心规则**：
- **业务链路边界**（HTTP 请求、RPC 派发、回调）：沿用上游 traceId，不得改写；
- **系统行为边界**（心跳、注册、对账、清扫、落库消费）：链路起点，生成新 traceId（或无业务 traceId）；
- **requestId 永不在服务边界重写**，只透传已有值——它是调度内部自闭环 ID。

## 7. 排障指南

- **按链路聚合**：用 `traceId` 检索，一次请求/调度的全段日志（HTTP 入口 → 派发 → Worker 执行 → 回调）全部命中。
- **定位单次执行**：用 `requestId` 精确检索某次调度执行，并关联 `schedule_rec` 与 `job_change`。
- **链路中断排查**：若某段日志 traceId 为空或不一致，优先检查该段是否经过 `MdcExecutorService.wrap` 的线程池、入口是否正确注入、是否误覆盖 traceId。
- **prod 检索**：JSON 日志按 `{"traceId":"..."}` 或 `{"requestId":"..."}` 过滤（ELK/Loki）。

## 8. 验证清单（新增链路后自测）

- [ ] cron 调度：派发 → Worker → 回调日志 traceId 一致（三者同值）
- [ ] 手动 exec：HTTP 入口 traceId=R1，回调 traceId 仍为 R1（贯穿），requestId 为 R2（唯一）
- [ ] 单次任务补触发：traceId=requestId 一致
- [ ] 回调失败/超时路径：日志 traceId/requestId 不缺失
- [ ] dev 文本日志含 `[traceId][requestId]`，prod JSON 含两字段
