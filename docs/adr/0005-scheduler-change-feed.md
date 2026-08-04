# 0005-scheduler-change-feed

Status: accepted

调度队列构建原先每秒全量扫描 `job` 表（游标分页、全列实体），在 10 万级 ENABLE 任务下存在 DB 性能、内存压力与调度延迟问题；我们决定引入**变更源（`job_change` 表）**做增量对账：所有作业元数据写路径与 Job 行写入同事务地追加一条变更记录，主节点每秒按 id 水印消费、回查当前行后与内存队列 diff，稳态成本从“∝ 任务总量”降为“∝ 变更量”；成为主节点时仍执行全量对账并把水印置为 `max(id)`，另保留 60s 周期全量兜底对账，抵御直改库与漏写。调度队列与 `queuedJobs` 只持有轻量投影（id/name/cron/executeParam/strategy/type），不再持有完整 Job 实体；`SchedulerEngine` 增加 `clear()`，主备切换清理从 O(n²) 降为 O(n)。单次任务维持“派发即摘除 + in-flight”语义，失败/超时/同步异常/常驻清扫释放 in-flight 时统一写“失败重试”变更记录重新入队；成功回调仍写“单次完成”记录，用于清理手动补跑与队列条目并存时残留的待触发条目。本 ADR 同时收紧 Finished 语义（补充 ADR-0003）：`finished` 仅在单次任务上有效（finished=1 ⇒ type=1），管理端把任务改为普通任务时同事务重置 finished=0，成功回调置位时增加 type=SINGLE 条件守卫，存量异常数据由迁移 SQL 修复。

## 考虑过的方案

- **每秒全量扫描 + 覆盖索引摘要 diff**：实现最简单，但成本仍 ∝ 任务总量，且要求 `update_time` 在所有写路径可靠维护；否决（可作为更小规模过渡方案，不作为目标方案）。
- **仅降低扫描频率（5s/30s）**：破坏已承诺的“秒级生效”，否决。
- **单次任务“成功后才从队列删除”**：可省去失败重试记录，但秒级 cron 下存在“成功提交 → 变更表处理”窗口内下个火点触发、造成 Finished 后重复执行的问题，且破坏“队列 = 可派发”语义；否决。
- **进程内事件通知替代变更源**：写路径可能落在 Standby 节点，主节点收不到事件；否决。

## 后果

- 新增 `job_change` 表与六个写路径埋点（注册/编辑/启停/单次完成/删除/失败重试）；除失败重试为独立插入外，其余均与 Job 行写入同事务。
- `switchStatus` 从 Controller 迁入 `JobService`，保证埋点的事务边界。
- 新增 `POST /admin/job/delete` 逻辑删除接口（`JobService.delete`，同事务 `removeById` + change_type=5 埋点）；删除后 Worker 重新注册因唯一键命中 `deleted=1` 旧行而不会复活任务。
- 调度器新增投影游标查询（`status = 1 AND finished = 0`）；`JobRep` 原全行游标方法保留给 `ScheduleRunRecovery`，避免隐式耦合。
- 引擎接口新增 `clear()`，`DELAY_QUEUE` 保持默认；`TIME_WHEEL` 仍可作为配置项。
- 消费端应用变更记录时，若任务处于 in-flight 则跳过该记录的整体 apply（此时队列必无该任务）；应用变更记录复用 `queuedJobs.compute` 幂等入队逻辑（已有条目且元数据未变则不重复 add，全量对账入队后到达的迟到 REQUEUE 不会重复入队），并在 `handle()` 中先置 in-flight 再摘除条目，闭合消费线程插入的竞态窗口。
- 派发吞吐（`dispatchThreads` 默认值、`registry.discover` 缓存）不在本 ADR 范围，记为未来项。
- 现有 FIXME 三条（性能/内存/延迟）由本方案设计解决，代码实施另起一轮。
