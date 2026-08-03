# 0004-admin-single-active-ha

Status: accepted

多 Admin 部署时，若各节点都运行 `JobScheduler` 会重复调度；我们决定采用**单活（Single-Active）HA**：同一时刻仅一个 Admin 持有调度权，通过 `LeaderElection` 抽象选主（DB 租约锁为默认实现、Redis 锁为可选实现），其余节点待命并继续提供注册与管理接口。陈旧 RUNNING 记录由主节点**常驻清扫**（默认 30s 一次）置 FAIL 并释放对应 in-flight；主节点故障后冷接管时立即清扫一次、为未派发过的单次任务补触发错过的火点、全量对账恢复调度；HA 模式下所有任务按 At-Least-Once 执行（双发窗口 ≤1s）。

## 考虑过的方案

- **多活分片**（按 jobId 划分所有权 + 租约 + fencing token）：可水平扩展，但复杂度高一个量级；当前没有调度吞吐瓶颈，作为未来演进方向，不在本方案实现。
- **`SELECT ... FOR UPDATE` 长事务持锁**：实现最少，但调度循环需长期占用连接与事务，断连/超时会误放锁，否决。
- **Redis 锁作为默认选主**：锁语义更顺滑，但会强制引入 Redis 部署依赖，否决（保留为可选实现）。
- **全部任务补触发错过的火点**：突发补偿与重复风险高，否决；仅单次任务补触发一次。
- **每次派发前查库验证所有权**：把派发路径绑定 DB RTT，否决；改用 1s 刷新的 `isLeader` 内存标志。

## 后果

- 新增单行锁表 `schedule_lock` 与 `schedule.ha.*` 配置（默认关闭，单节点行为不变）。
- Worker SDK 支持多 Admin 地址（逗号分隔）与 `AdminNodeSelector`（轮询默认/随机/哈希），注册与心跳故障转移。
- ADR-0002 第二阶段（集群协调轮询）被本方案取代：单活下同一时刻仅一个派发者，派发计数器维持内存态。
- 主备切换窗口内普通 Cron 任务可能跳过一次火点（不补偿）；单次任务错过即补，重复执行由 Worker 幂等兜底。
- 单次任务的 At-Least-Once 由五层保障：派发即写 RUNNING → 内存超时（reqTimeout）置 FAIL 并释放 in-flight → 主节点常驻清扫兜底 → 接管补触发未派发火点 → 下次 Cron 自然重试；"RUNNING" 的定义见 CONTEXT.md。
