# 调度队列构建可扩展性改造 Spec（2026-08-04）

> 本 Spec 由 grill-with-docs 会话逐项决策固化而来，解决 `JobScheduler#asyncBuildScheduleJobs` 的 FIXME：每秒查询全量任务的性能问题、全量加载到内存的压力、任务量极大时的调度延迟。实施计划与代码改动另起一轮，本轮只固化决策。

## 1. 背景与目标

现状：`asyncBuildScheduleJobs` 每 1s 调用 `reconcileQueuedJobs()`，游标分页拉取全部 ENABLE 任务的**完整实体**与内存队列 diff，成本 ∝ 任务总量。

目标：单 Admin 集群支撑 **10 万个 ENABLE 任务**，管理端变更（创建/编辑/启停/删除/完成/失败重试）**≤1s 生效**；稳态对账成本 ∝ 变更量而非任务总量。

百万级任务与派发吞吐不在本轮范围（见第 8、12 节）。

## 2. 决策清单（已确认，状态：accepted）

| # | 决策 | 结论 |
|---|------|------|
| 1 | 目标规模 | 10 万 ENABLE 任务；百万级为未来演进（多活分片） |
| 2 | 变更生效时效 | ≤1s 感知，不放宽 |
| 3 | 对账机制 | **变更源（job_change）增量对账**；成为主节点时全量对账 + 60s 周期全量兜底 |
| 4 | 变更表结构 | id / job_id / change_type / operator / request_id / job_name / create_time(DATETIME(3)) |
| 5 | 消费模型 | 主节点每秒按 id 水印轮询（LIMIT 500），回查当前行 diff；接管后水印置 max(id)；每 5 分钟清理已消费记录 |
| 6 | 埋点清单 | 注册 1 / 编辑 2 / 启停 3 / 单次完成 4 / 删除 5 / 失败重试 6；与 Job 写入同事务（6 独立插入） |
| 7 | 队列语义 | 单次任务派发即摘除 + in-flight；任何释放 in-flight 的路径统一写“失败重试”记录重新入队 |
| 8 | 内存模型 | 队列与 queuedJobs 只持轻量投影 record：id/name/cron/executeParam/strategy/type |
| 9 | 全量对账查询 | 调度器新增投影游标查询 `status = 1 AND finished = 0`；Java 侧 `isFinishedSingleRun` 分支删除 |
| 10 | Finished 不变量 | finished=1 ⇒ type=1；edit 改 type→普通时同事务重置 finished=0；成功回调置位加 type=SINGLE 守卫；存量数据迁移修复 |
| 11 | 引擎 | `DELAY_QUEUE` 默认不变；`SchedulerEngine` 增加 `clear()`；主备切换清理 O(n) |
| 12 | 消费端守卫 | 应用变更记录时若任务 in-flight 则跳过该记录的整体 apply（此时队列必无该任务）；复用 `queuedJobs.compute` 幂等入队（已有条目且元数据未变不重复 add），`handle()` 先置 in-flight 再摘除条目 |
| 13 | 范围 | “调度延迟”按构建/感知延迟收敛；派发吞吐记为未来项 |
| 14 | 文档与代码 | 本轮只固化文档（CONTEXT.md / ADR-0005 / Spec / schema.sql）；FIXME 保留为实施锚点 |

## 3. 变更表结构与消费模型

```sql
CREATE TABLE IF NOT EXISTS `job_change` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `job_id`      BIGINT       NOT NULL COMMENT '作业 ID',
    `change_type` TINYINT      NOT NULL COMMENT '1注册 2编辑 3启停 4单次完成 5删除 6失败重试',
    `operator`    VARCHAR(64)  DEFAULT NULL COMMENT '操作人（管理端用户名或 system）',
    `request_id`  VARCHAR(64)  DEFAULT NULL COMMENT '调度追踪 ID（单次完成/失败重试时关联 ScheduleRec）',
    `job_name`    VARCHAR(128) DEFAULT NULL COMMENT '冗余作业名，便于排查',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='作业变更源（增量对账消费）';
```

规则：

1. 消费端**无视 change_type**，只以 `job_id` 回查当前行再 diff——记录与库内状态不一致时以回查为准，天然幂等；change_type 等字段仅供排查。
2. 主节点每秒轮询 `WHERE id > watermark ORDER BY id LIMIT 500`；对每条记录：`getById(jobId)`，按“不存在 / 禁用 / Finished 单次任务 / 元数据变化 / 其余”分别执行移除、替换或保留。
3. 应用变更记录前检查 `singleRunTracker.contains(jobId)`：在途则跳过该记录的整体 apply（此时队列必无该任务），防止跨主延迟消费 REQUEUE 造成并发双发。
4. 成为主节点流程：`recover()` → `engine.clear()` + `start()` → 全量对账 → `watermark = max(id)`，不重放历史。
5. 周期兜底：每 60s 执行一次现有全量对账（改走投影查询），自愈直改库、漏写等 diff 漂移。
6. 清理：每 5 分钟删除 `id <= watermark` 的记录，表保持近空。
7. 应用变更记录复用 `queuedJobs.compute` 幂等入队逻辑：已有条目且元数据未变则不重复 add——全量对账入队后到达的迟到 REQUEUE 不会造成重复入队；`handle()` 先置 in-flight 再摘除队列条目，闭合消费线程插入的竞态窗口。

## 4. 埋点清单与事务边界

| 写路径 | change_type | 事务边界 | 备注 |
|---|---|---|---|
| `ScheduleJobService.registerJob` 新建成功 | 1 注册 | 与 `jobRep.save` 同事务 | DuplicateKeyException 不写 |
| `JobService.edit` | 2 编辑 | 与 `updateById` 同事务 | type→普通时同事务重置 finished=0 |
| `JobService.switchStatus`（从 Controller 迁入） | 3 启停 | 与 `updateById` 同事务 | 现逻辑在 Controller，迁移时补埋点 |
| `ScheduleRecCallback.onSuccess` 单次任务置 Finished | 4 单次完成 | 与 finished 更新同事务 | 带 request_id；更新加 `.eq(type, SINGLE)` 守卫 |
| `JobService.delete`（新增 `/admin/job/delete`） | 5 删除 | 与 `removeById` 同事务 | 逻辑删除（deleted=1）；id 不存在抛 ScheduleException；重复删除报错 |
| `ScheduleRecCallback.onFailure` 单次任务失败 | 6 失败重试 | 独立插入 | 覆盖超时/连接断开等异常完成路径 |
| `handle()` 同步异常 catch（单次任务） | 6 失败重试 | 独立插入 | 释放 in-flight，修复现状卡到常驻清扫的缺口 |
| `ScheduleRunRecovery.releaseStaleInFlight` 释放 | 6 失败重试 | 独立插入 | 与失败回调保持同一机制，不加进程内捷径 |

不变量：**任何释放 in-flight 的路径都必须写“失败重试”变更记录**，由主节点 ≤1s 内消费重新入队。

删除语义：逻辑删除（`deleted=1`），不打断在途执行——已派发的单次任务照常完成并写 ScheduleRec，失败重试的 REQUEUE 被消费时回查不到任务而不再入队；消费端零改动（回查“不存在”即移除队列条目）；Worker 重新注册因 `uk_group_name_name` 唯一键命中 `deleted=1` 旧行而**不会复活任务**。

## 5. 内存模型

- 队列与 `queuedJobs` 持有轻量投影 record：`id, name, cron, executeParam, strategy, type`（`status` 由查询过滤、`finished` 由查询条件过滤，均不进投影）。
- 10 万任务队列内存从完整实体（约 100~200MB）降到投影（约 10~20MB）。
- 调度器全量对账改用新投影游标查询：`WHERE deleted=0 AND status=1 AND finished=0 AND id > ? LIMIT 1000`，只选投影列；`JobRep.batchQueryJobsByCursor` 全行方法保留给 `ScheduleRunRecovery.catchUpMissedSingleRuns`（接管补触发需要完整字段，且仅接管时执行）。

## 6. 引擎与主备切换

- `SchedulerEngine` 接口新增 `clear()`；`DelayQueueSchedulerEngine` 与 `TimeWheelSchedulerEngine` 各自实现（清空内部队列/时间轮与就绪队列）。
- `onLoseLeadership()` 改用 `engine.clear()` 替代逐条 `remove`，切换清理从 O(n²) 降为 O(n)；`onBecomeLeader()` 全量对账前也先 `clear()`。
- `DELAY_QUEUE` 保持默认；稳态下其 add/take 均为 O(log n)，10 万级可接受；`TIME_WHEEL` 保留为配置项，百万级或高变更频率时再评估。

## 7. Finished 不变量（补充 ADR-0003）

1. `finished=1 ⇒ type=1`：该标记仅在单次任务上有效。
2. `JobService.edit` 将 type 改为普通任务（0）时，同事务置 `finished=0`。
3. `ScheduleRecCallback.onSuccess` 置位条件增加 `.eq(type, SINGLE)`：派发后、回调前被改成普通任务时命中 0 行，不置位。
4. 存量迁移：`UPDATE job SET finished = 0 WHERE type = 0 AND finished = 1;`（幂等，随建表脚本执行）。

## 8. 范围

### In Scope（实施轮）

- `job_change` 建表与存量迁移；六个写路径埋点与事务边界。
- 新增 `POST /admin/job/delete` 删除接口（逻辑删除 + change_type=5 埋点，语义见第 4 节）。
- 主节点变更源消费（水印/回查 diff/清理）+ 60s 周期兜底 + 接管流程。
- 调度器轻量投影、投影游标查询、`isFinishedSingleRun` 分支清理。
- `SchedulerEngine.clear()` 与主备切换改造。
- 文档：CONTEXT.md、ADR-0005、本 Spec、schema.sql。

### Out of Scope

- 派发吞吐优化（`dispatchThreads` 默认值、`registry.discover` 缓存/批量、同一秒大量任务到期）——未来项。
- 百万级任务（多活分片 + 变更源分区/归档）——未来项。
- 管理后台前端。

## 9. 关键约束

- JDK 21；Spring Boot 3.5.6；Netty 4.1.108.Final；MyBatis-Plus 3.5.7；**不引入新的运行时依赖**。
- 回调与消费不得在 Netty I/O 线程执行（沿用既有回调执行器约束）。
- HA 关闭时行为与现状一致：单节点恒为主，变更源照常写入与消费，不影响既有调度语义。
- 变更记录与 Job 写入同事务：`job_change` 表不可用会阻断作业写入，属已接受风险（同一数据库集群）。

## 10. 验收标准（实施轮）

1. `job_change` 建表与迁移 SQL 可重复执行，存量异常行（type=0 且 finished=1）被清零。
2. 稳态下 1s 内：编辑 cron/参数/策略、启停、注册、单次完成、失败重试均被主节点消费并反映到调度队列。
3. 10 万任务内存：队列仅持投影（目标 <50MB）；每秒轮询查询量只随变更量增长。
4. 主备切换：`clear()` 生效，10 万任务切换清理在秒级完成；接管后水印置 `max(id)`，不重放历史记录。
5. 单次任务：派发即摘除并置 in-flight；失败/超时/同步异常/常驻清扫释放后 ≤1s 重新入队；成功后不再入队；手动补跑与队列条目并存时 ≤1s 移除残留条目；type 改普通后 finished 重置且回调不再置位。
6. 跨主场景：新主全量对账后延迟消费旧主的 REQUEUE 记录不会造成并发双发。
7. 全量 `mvn test` 通过。
8. 删除接口：删除 ENABLE 任务后 ≤1s 内队列条目被移除；在途单次任务执行结果照常落库、失败重试不再入队；重复删除抛“任务不存在”；Worker 重新注册不会复活任务。

## 11. 风险与回滚

- 新增 `job_change` 表：回滚删除表并移除埋点即可；未消费记录最多影响增量，60s 兜底全量对账兜住正确性。
- 埋点同事务：变更表故障会阻断作业写路径，已接受；监控 `job_change` 表可用性与消费水位。
- Finished 重置改变编辑语义：type→普通 会清 finished，历史完成记录仍完整保留在 schedule_rec。
- 消费水印为内存态：主节点重启/切换后由“全量对账 + max(id)”重建，不依赖持久化水印。

## 12. 未来项

- **派发吞吐**：评估 `schedule.dispatch-threads` 默认值与调优建议、`registry.discover` 缓存/批量、同一秒大量任务到期的吞吐模型。
- **百万级**：多活分片（jobId 所有权 + 租约 + fencing）、变更源分区与归档。
