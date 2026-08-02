# 调度框架二期重构 Spec（2026-08-02）

> 本 Spec 由 grill-with-docs 会话的 12 项决策固化而来，是后续实施计划（docs/superpowers/plans/2026-08-02-phase-a-*.md、phase-b-*.md）的需求来源。

## 1. 背景与目标

项目已完成第一轮审查与修复（调度对账去重、Cron 纳秒计算、RPC 超时回调、负载均衡补齐等）。本轮在评审中识别出以下待定语义与遗留问题，经逐项决策后形成本 Spec：

1. 单次任务（Single-Run Job）的执行保证契约未定义清楚；
2. 单次任务成功/失败后的状态流转不闭环，且终态与管理态（ENABLE/UNABLE）混用；
3. 调度主循环单线程派发缺少扩展性；
4. 客户端建连是阻塞式 `connect().sync()`，可能卡住派发线程；
5. RPC 回调注册机制硬编码在 `ScheduleJobClient#send`，无法扩展；
6. 回调执行器与超时清理线程没有优雅关闭；
7. `CronExpression.parse` 重复解析；
8. 配置文件含明文密码与 SQL 控制台日志，且业务配置未按环境隔离；
9. 存在僵尸 Registry 类与缺失的集成测试；
10. 编辑接口缺少 cron 校验。

## 2. 决策清单（已确认，状态：accepted）

| # | 决策 | 结论 |
|---|------|------|
| 1 | 单次任务执行保证 | **至少一次（At-Least-Once）**；Worker 必须幂等；Admin 重启/响应丢失可能重发 |
| 2 | 失败行为 | 失败回调移除 in-flight 标记，由定时扫描按 Cron 自然重试；成功回调置 **Finished 终态** |
| 3 | 终态建模 | Job 新增 `finished` 列（0/1），与 `status`（ENABLE/UNABLE 管理态）解耦 |
| 4 | 手动补跑 | `finished=1` 的任务允许 `/admin/job/exec` 补跑，终态不变，只写 ScheduleRec |
| 5 | 派发模型 | jobId 分片线程池，默认 1 线程，`schedule.dispatch-threads` 可配置 |
| 6 | 建连 | `ChannelManager` 改为异步建连，不阻塞派发线程 |
| 7 | 回调 API  | 保留可扩展 `ScheduleCallback`（带 request/job 上下文），注册表由 Spring 收集、@Order 排序 |
| 8 | 回调派发 | 回调在 `ScheduleFuture.complete/completeExceptionally` 内部派发（专用执行器，异步） |
| 9 | 异常隔离 | 单个回调异常不影响其他回调（try/catch + log） |
| 10 | 优雅关闭 | 清理任务改单线程调度线程池；回调执行器 `shutdown + awaitTermination(3s) + shutdownNow`；NettyLifecycle 统一触发 |
| 11 | Cron 解析 | 单次解析 + IllegalArgumentException 包装；`checkCronExpression` 保留给注册/编辑校验 |
| 12 | 配置环境 | 拆 `application.yml`（仅公共骨架）/ `application-dev.yml` / `application-prod.yml`；业务配置按环境隔离；prod 用无默认值环境变量 |
| 13 | Redis 集群轮询 | **搁置**（ADR-0002 已标暂缓），后续单独设计 |
| 14 | 死代码 | 删除 `LocalCacheJobInstanceRegistry`、`PersistJobInstanceRegistry`；`RemoteRegisterCenterRegistry.unregister` 空实现加注释 |
| 15 | 集成测试 | 新增 Netty 回环集成测试（成功 + 未知任务失败路径） |
| 16 | 编辑校验 | `JobService.edit` 中 cron 非空时校验，非法抛 `ScheduleException` |

## 3. 范围

### In Scope

- Admin 模块：调度语义、派发、回调、建连、配置、校验、清理。
- Common 模块：无改动（时间轮已在本轮之前完成修复）。
- Core 模块：仅测试范围新增 admin 对 core 的 test-scope 依赖（用于 Netty 回环测试）。
- 文档：`docs/sql/schema.sql` 增加 `job.finished` 列；CONTEXT.md/ADR 已在本轮会话更新完毕。

### Out of Scope

- Redis 集群级轮询（ADR-0002 第二阶段，已搁置）。
- MySQL/Redis 真库集成测试（无 Docker 环境，待后续）。
- Worker 侧（core）生产代码改动。
- 管理后台前端。

## 4. 关键约束

- JDK 21；Spring Boot 3.5.6；Netty 4.1.108.Final；MyBatis-Plus 3.5.7；fastjson2 2.0.43；**不引入新的运行时依赖**。
- 严禁在 Netty I/O 线程执行 DB/网络 I/O；回调必须走专用执行器。
- 业务异常派生自 `ScheduleException`。
- 构建验证：`mvn test`（JAVA_HOME=`C:\Users\wangyang\.jdks\ms-21.0.10`；需内网代理连通 Nexus）。
- 执行前先提交当前工作区改动为基线 commit（需用户确认）。

## 5. 验收标准

### 阶段 A（调度语义与派发）

1. 单次任务成功执行回调后：`job.finished = 1`、`job.status` 不变、不再进入调度队列；再次 reconcile 不重复入队。
2. 单次任务失败回调后：in-flight 标记被移除；若任务仍 ENABLE，下次 reconcile 按 Cron 重新入队。
3. Admin 重启后单次任务可能重发（At-Least-Once，文档契约，不做持久化）。
4. `schedule.dispatch-threads` 默认 1，配置 N 时按 `floorMod(jobId, N)` 分片，同一 jobId 串行、不同 jobId 可并行。
5. 建连失败/发送失败均走失败回调（ScheduleRec 置 FAIL），不阻塞派发线程。
6. 编辑接口校验 cron；非法 cron 返回业务错误。
7. 僵尸 Registry 类删除；`unregister` 空实现有注释说明。
8. Netty 回环集成测试通过（成功路径 + 未知任务失败路径）。

### 阶段 B（RPC 回调与基础设施）

1. `ScheduleCallback` 接口 + `ScheduleCallbackContext(request, jobId, singleRun)` + Spring 收集的注册表；
2. 内置回写逻辑拆为 `ScheduleRecCallback`（@Order(0)），行为与阶段 A 完全一致；
3. `ScheduleFuture` 内部异步派发回调，异常隔离；
4. `ScheduleRequestHandler` 只负责"完成 Future"，不再持有回调执行器；
5. 超时清理使用单线程调度线程池，`shutdown()` 可停止；回调执行器有界等待关闭；
6. profile 拆分完成：dev/prod 业务配置隔离，prod 无明文密钥；
7. 全量 `mvn test` 通过。

## 6. 风险与回滚

- `finished` 列属于 schema 变更：回滚需删除列或忽略字段（实体不加字段即可兼容旧库）。
- 异步建连改变 send 时序：通过集成测试与既有超时机制兜底。
- 分片派发默认 1 线程与现行为一致，风险低。
