# Job Distributed Scheduler

这是一个轻量级、高性能的分布式任务调度框架，用于解决微服务架构下定时任务的统一管理、高可用调度、弹性扩容及可观测性。

## Language

**Admin (调度中心 / Admin 集群)**:
承载作业元数据、实例发现、调度记录生成与任务派发的服务端；多个 Admin 节点共享同一数据库构成 Admin 集群。
_Avoid_: server, 服务端

**Single-Active (单主 / 单活)**:
Admin 集群的一种运行模式：同一时刻仅一个 Admin 节点拥有调度权，其余节点待命（Standby），主节点故障后由其他节点接管。
_Avoid_: 主从, active-standby

**Leader (主节点 / 主)**:
Admin 集群中持有调度权、负责任务对账与派发的节点；同一时刻至多一个。
_Avoid_: master, primary

**Standby (待命节点)**:
Admin 集群中不持有调度权、仅提供任务注册与管理接口的节点，主节点故障时可接管。
_Avoid_: slave, backup, 备机

**Job (作业 / 任务)**:
描述一个被调度的最小业务逻辑单元，包含其执行策略、Cron 表达式、执行参数及任务组信息。
* **Normal Job (普通任务)**: 依照 Cron 表达式周期性、循环调度的任务。
* **Single-Run Job (单次任务)**: 仅需执行一次的任务，执行保证为**至少一次（At-Least-Once）**——Admin 重启或响应丢失时可能重发，因此执行方（Worker）必须幂等。执行失败后移除 in-flight 标记，由定时扫描按 Cron 自然重试；执行成功后其状态流转为终态 **Finished（已完成）**，并从活动调度队列中剔除。
* **Finished（已完成）**: 单次任务的业务终态，表示该任务已成功执行过一次，与任务是否可被管理端调度（**UNABLE / ENABLE**）相互独立——Finished 的单次任务即使处于 ENABLE 也不会进入调度队列；该标记仅在单次任务上有效，管理端把任务类型改为普通任务时会被重置。
_Avoid_: Task, schedule

**Worker (执行器节点 / 运行节点)**:
代表承载任务执行的具体客户端物理进程或容器，包含 IP、Netty 端口以及活性心跳状态。
_Avoid_: JobInstance, client, instance, node

**ScheduleRecord (调度记录 / 执行记录)**:
代表调度中心触发的每一次具体的任务执行生命周期记录，包含追踪 ID、调度时间、完成时间及最终执行状态（RUNNING / SUCCESS / FAIL）。
_Avoid_: ScheduleRec, log, execution, run

**RUNNING（执行中）**:
ScheduleRecord 的非终态：调度中心已登记本次执行并等待 Worker 回调；超过 reqTimeout + 宽限仍无终态时，由主节点常驻清扫或接管恢复置为 FAIL。
_Avoid_: in-flight（in-flight 是 SingleRunTracker 的进程内任务级标记，不是记录状态）

**Reconcile（对账）**:
主节点使调度队列与作业持久化状态保持一致的过程：稳态由变更源增量驱动，成为主节点或周期兜底时全量执行。
_Avoid_: 同步, refresh

**Change Feed（变更源）**:
记录作业元数据每次变更（创建、编辑、启停、完成、删除、失败重试）的持久化消息流；主节点消费它做增量对账，使管理端变更在秒级作用于调度队列。
_Avoid_: outbox, 变更日志

**In-Flight（在途）**:
单次任务已从调度队列摘除、派发执行并等待结果回调的进程内状态；成功置 Finished、失败/超时/常驻清扫释放时清除。
_Avoid_: 执行中（执行中是 ScheduleRecord 的状态，不是队列侧标记）

**TraceId（链路追踪 ID / R1）**:
标识一次完整请求/调度的链路 ID，**贯穿请求 → 调度 → Worker 执行 → 回调全程不变**。HTTP 层由 `X-Request-Id` 注入（外部可传、缺失生成），cron/补触发场景由调度入口生成（链路起点）。日志按 traceId 聚合整条链路。
_Avoid_: 中途改写（traceId 一旦注入不得修改）

**RequestId（调度执行 ID / R2）**:
标识**单次调度执行**的内部 ID，每次调度唯一，与 `schedule_rec.requestId` 一致；自闭环处理（不外泄、不在服务边界重写），供按 requestId 定位单次执行。业务同步代码只从 MDC 读取、不注入。
_Avoid_: 与 traceId 混用（两者语义不同，共用 key 会导致链路 traceId 中途变化）
