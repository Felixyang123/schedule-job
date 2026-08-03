# 0002-job-level-and-cluster-coordinated-round-robin

> 状态：第一阶段（作业级隔离轮询）已实现；第二阶段（集群级协调轮询）**暂缓**——涉及多 Admin 高可用场景，需要严格设计，待其余工作完成后另行讨论实现。

为了解决全局共享计数器导致的轮询混杂问题，并支持未来调度中心（Admin）集群部署时的高可用均衡，我们决定重构负载均衡器中的轮询选择策略，实施“作业级隔离轮询”和“集群级协调轮询”。

## 上下文与痛点
原本的 `RoundRobinSelector` 使用单个全局 `AtomicInteger`。这导致：
1. 多个不同的作业（Job A、Job B）交替调度时，它们共用同一个计数器，破坏了单个作业在 Worker 集群中的严格轮询规律。
2. 当多台调度中心（Admin）集群部署时，各自内存中的计数器无法同步，导致整体分发失去均衡性。

## 决策决定
我们将轮询策略划分为两个演进阶段与运行模式：

1. **作业级隔离轮询 (Job-Level Isolated Round Robin)**：
   - 调度中心本地内存中使用 `ConcurrentHashMap<String, AtomicInteger>`，其中 Key 为作业唯一的 `discoveryKey` 或 `jobId`。
   - 每次选择节点时，仅针对该特定作业的计数器执行 `getAndIncrement()`，确保单个作业在单节点 Admin 环境下严格均匀轮询。

2. **集群级协调轮询 (Cluster-Level Coordinated Round Robin)**：
   - 在多 Admin 高可用部署模式下，如果开启了分布式存储（如 Redis），轮询计数器状态应当迁移至分布式共享缓存。
   - 使用 Redis 提供的原子自增命令（如以 `job:lb:counter:{discoveryKey}` 为 Key 进行 `INCR` 操作）来提供严格的全局一致性计数，确保多台 Admin 协同调度同一组任务时，请求仍能被均匀打散到各个 Worker 节点。

## 考量与权衡
* **优点**：既保障了单机部署下的轻量与精确性（作业级内存隔离），又保留了在生产集群环境下横向扩展时的严格负载均衡能力。
* **代价**：当开启集群轮询时，增加了对 Redis 的依赖和一次额外的 Redis 网络 RTT 损耗（通常在 1-2ms 内，对于调度场景可忽略不计）。

---

> **状态补充（2026-08-03）**：第二阶段（集群级协调轮询）被 [ADR-0004](./0004-admin-single-active-ha.md) 的单活 HA 方案取代并正式关闭——单活保证同一时刻仅一个 Admin 节点派发，跨节点计数器协调不再需要；若未来演进为多活分片，再重新评估 Redis 计数器。
