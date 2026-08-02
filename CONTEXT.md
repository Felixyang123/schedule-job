# Job Distributed Scheduler

这是一个轻量级、高性能的分布式任务调度框架，用于解决微服务架构下定时任务的统一管理、高可用调度、弹性扩容及可观测性。

## Language

**Job (作业 / 任务)**:
描述一个被调度的最小业务逻辑单元，包含其执行策略、Cron 表达式、执行参数及任务组信息。
* **Normal Job (普通任务)**: 依照 Cron 表达式周期性、循环调度的任务。
* **Single-Run Job (单次任务)**: 仅需执行一次的任务，执行保证为**至少一次（At-Least-Once）**——Admin 重启或响应丢失时可能重发，因此执行方（Worker）必须幂等。执行失败后移除 in-flight 标记，由定时扫描按 Cron 自然重试；执行成功后其状态流转为终态 **Finished（已完成）**，并从活动调度队列中剔除。
* **Finished（已完成）**: 单次任务的业务终态，表示该任务已成功执行过一次，与任务是否可被管理端调度（**UNABLE / ENABLE**）相互独立——Finished 的单次任务即使处于 ENABLE 也不会进入调度队列。
_Avoid_: Task, schedule

**Worker (执行器节点 / 运行节点)**:
代表承载任务执行的具体客户端物理进程或容器，包含 IP、Netty 端口以及活性心跳状态。
_Avoid_: JobInstance, client, instance, node

**ScheduleRecord (调度记录 / 执行记录)**:
代表调度中心触发的每一次具体的任务执行生命周期记录，包含追踪 ID、调度时间、完成时间及最终执行状态。
_Avoid_: ScheduleRec, log, execution, run
