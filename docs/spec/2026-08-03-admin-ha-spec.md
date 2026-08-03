# Admin 单活 HA 改造 Spec（2026-08-03）

> 本 Spec 由 grill-with-docs 会话逐项决策固化而来，是实施计划（[Phase A](../../docs/superpowers/plans/2026-08-03-admin-ha-phase-a.md)、[Phase B](../../docs/superpowers/plans/2026-08-03-admin-ha-phase-b.md)）的需求来源。ADR-0002 第二阶段（集群协调轮询）因涉及多 Admin 高可用场景而被暂缓，本 Spec 以"单活（Single-Active）"方案整体解决。

## 1. 背景与目标

1. 多 Admin 部署时，每个节点的 `JobScheduler` 都会全量扫描并调度，任务会被 N 倍触发，HA 不安全；
2. `SingleRunTracker` 与派发轮询计数器均为节点内存态，跨节点无法共享；
3. Worker 注册/心跳指向单一 Admin 地址，Admin 故障时 Worker 失联；
4. 已派发未回执的任务（RUNNING 记录）在旧主死亡后无人清理，可能卡到下一个 Cron 周期；
5. 主备切换窗口内错过的 Cron 火点需要明确语义（跳过 or 补偿）；
6. Worker 侧需要多 Admin 地址的标配能力（多地址、节点选择、故障转移）。

## 2. 决策清单（已确认，状态：accepted）

| # | 决策 | 结论 |
|---|------|------|
| 1 | HA 形态 | **单活（Single-Active）**：同一时刻仅一个 Admin 持有调度权，其余待命 |
| 2 | 选主机制 | **LeaderElection 抽象**：DB 租约锁（默认）+ Redis 锁（可选），无新运行时依赖 |
| 3 | 租约参数 | 租约 10s / 续约 3s / 轮询 1s；节点唯一 ID 默认 `host:port`，可配置覆盖 |
| 4 | Standby 行为 | HTTP 注册/管理接口常开；调度（对账、派发、回调）为主节点专属；冷接管，不预热队列 |
| 5 | 切换语义 | 失去主：停 engine、清空 queuedJobs 与 in-flight；成为主：接管恢复 → 全量对账 → 启动引擎 |
| 6 | 派发门控 | 派发前检查 `isLeader`（选举循环 1s 刷新），双发窗口 ≤1s |
| 7 | 执行契约 | HA 模式下**所有任务 At-Least-Once**；普通任务切换窗口可能重复一次，业务侧自行幂等 |
| 8 | 错过火点 | 普通任务跳过；**单次任务接管时补触发一次**（未派发过才补） |
| 9 | 陈旧记录 | **常驻清扫（主节点，默认 30s）**：把超过 `reqTimeout + 5s` 的 RUNNING 记录置 FAIL 并释放对应 in-flight；接管时立即清扫一次，恢复自然重试语义 |
| 10 | Worker 地址 | `serverAddress` 支持多地址（逗号分隔，List 宽松绑定），注册/心跳单目标 + 故障转移 |
| 11 | 节点选择 | **AdminNodeSelector** 抽象：ROUND_ROBIN（默认）/ RANDOM / HASH；hash key=实例键 |
| 12 | 派发计数器 | 维持内存态；ADR-0002 第二阶段被单活方案取代并关闭 |
| 13 | 开关默认 | `schedule.ha.enabled=false`（单节点行为不变）；`schedule-job.server-selector=ROUND_ROBIN` |
| 14 | 回调缺失保障 | 单次任务 At-Least-Once 保障链：派发即写 RUNNING → 内存超时（reqTimeout）置 FAIL 并释放 in-flight → 常驻清扫兜底 → 接管补触发未派发火点 → 下次 Cron 自然重试；重复执行由 Worker 幂等兜底 |

## 3. 范围

### In Scope

- Admin：`LeaderElection` 抽象（DB/Redis）、`ScheduleLeaderElector`、`JobScheduler` 门控与切换、`ScheduleRunRecovery`（常驻陈旧 RUNNING 清扫 + 单次任务补触发）、`CronUtils.getPreviousExecution`、`schedule_lock` 表、`schedule.ha.*` 配置。
- Worker SDK：`serverAddress` 多地址、`AdminNodeSelector` 抽象与实现、`DefaultRemoteJobRegistry` 故障转移、示例配置。
- 文档：CONTEXT.md、ADR-0002 状态、ADR-0004、schema.sql。

### Out of Scope

- 多活分片（jobId 所有权 + 租约 + fencing）——单活已满足可用性目标，未来需要水平扩展时再评估。
- Redis 派发计数器（ADR-0002 原第二阶段方案）。
- 管理后台前端改造。
- MySQL/Redis 真库集成测试（无 Docker 环境，沿用单测 + Mockito）。

## 4. 关键约束

- JDK 21；Spring Boot 3.5.6；Netty 4.1.108.Final；MyBatis-Plus 3.5.7；**不引入新的运行时依赖**（Redis 锁复用既有 `spring-boot-starter-data-redis`）。
- 业务异常派生自 `ScheduleException`；Netty I/O 线程禁止 DB/网络 I/O。
- HA 关闭时行为与现状完全一致（向后兼容）。
- 构建验证：`mvn test`（JAVA_HOME=`C:\Users\wangyang\.jdks\ms-21.0.10`；需内网代理连通 Nexus）。
- 执行前先提交当前工作区改动为基线 commit（需用户确认）。

## 5. 验收标准

### 阶段 A（Admin 单活 HA）

1. `schedule.ha.enabled=false` 时，单节点调度行为与现状一致（对账、派发、回调全通）。
2. 开启 HA 后：同一时刻仅一个节点成为 Leader（DB 租约 CAS 唯一性）；Standby 不派发。
3. 主节点故障（模拟租约过期）：备节点在 ~12s 内接管并恢复调度。
4. 接管时：陈旧 RUNNING 记录（> `reqTimeout + 5s`）被置 FAIL；未派发过的单次任务立即补触发一次（产生新 RUNNING 记录）；已派发过的不重复补。
5. 失去主：engine 停止、queuedJobs 清空、in-flight 清空；重新抢回主后重新对账。
6. 派发前 `isLeader` 检查生效：非主节点即使队列有残留也不派发。
7. Redis 选举（`schedule.ha.election=REDIS`）单测通过（setIfAbsent/续约/释放语义）。
8. 全量 `mvn test` 通过。
9. 常驻清扫：主节点存活时，超过 `reqTimeout + 5s` 且无回调的 RUNNING 记录被置 FAIL，对应的单次任务 in-flight 被释放并按 Cron 重试；清扫间隔 `ha-stale-sweep-seconds`（默认 30s）可配置；非主节点不执行清扫。

### 阶段 B（Worker SDK 多地址）

1. `serverAddress` 单值、逗号多值均可绑定；旧配置零改动。
2. 注册/心跳：选择器选主目标，失败顺序转移；一次成功即结束；全部失败仅告警不抛异常。
3. ROUND_ROBIN / RANDOM / HASH 三个选择器单测通过（HASH 对同一 key 稳定）。
4. 示例配置包含多地址与 `server-selector` 说明。
5. 全量 `mvn test` 通过。

## 6. 风险与回滚

- `schedule_lock` 为新增表，老库需执行建表脚本；回滚只需删除该表并关闭 `ha.enabled`。
- 单次任务补触发依赖"最近 ScheduleRec 与上次 Cron 触发点"判断，存在边界误差（任务被编辑/手动补跑），影响为多补或少补一次，在 At-Least-Once 契约内可接受。
- `isLeader` 内存标志在极端分区下仍有 ≤1s 双发窗口，属已接受契约（决策 #7）。
- 多地址改造涉及 SDK 构造签名变更，保留旧 String 构造重载以兼容现有调用方。
- 常驻清扫与内存超时路径并存，可能对同一记录重复置 FAIL（幂等更新，无副作用）；清扫间隔默认 30s，最坏情况下陈旧记录延迟约一个周期才被清理。
