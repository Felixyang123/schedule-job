# 生产加固（Production Hardening）Spec（2026-08-05）

> 本 Spec 由 grill-with-docs review 会话逐项决策固化而来。针对代码审查发现的 10 项问题（安全、可靠性、可观测性、资源管理）给出生产加固方案。实施计划另起一轮（`docs/superpowers/plans/2026-08-05-production-hardening.md`），本轮只固化决策。

## 1. 背景与目标

现状：调度框架在**开发/演示环境**下运行良好，但以生产标准审视存在 10 项缺陷：

| # | 类别 | 问题 |
|---|------|------|
| 1 | 安全 | 开放接口 `/open/**` 与管控接口 `/admin/**` 完全无鉴权，`accessToken` 形同虚设 |
| 2 | 性能 | RPC 回调在静态单线程执行，且内含 DB 事务，成为调度闭环吞吐瓶颈 |
| 3 | 可靠性 | Worker 心跳 HTTP 无超时，Admin 不可达时心跳线程可能长期阻塞 |
| 4 | 正确性 | `RedisJobInstanceStorage.list` 对已过期索引返回 `null` → NPE，派发路径必现 |
| 5 | 可靠性 | `ScheduleRecQueue` 落库失败静默丢弃，审计/对账载体失真 |
| 6 | 资源管理 | `ChannelManager` / `ScheduleRequestHandler` / `ScheduleFuture` 静态单例，重启后清理线程失效、跨上下文残留 |
| 7 | 可观测性 | 全系统无运行时指标，调度故障（对账滞后/回调积压）只能翻日志 |
| 8 | 资源管理 | 多 `SmartLifecycle` 停机顺序未编排，可能"先断网、后停调度" |
| 9 | 安全 | Worker 侧 Netty RPC 端口无鉴权，可伪造请求触发任意已注册任务 |
| 10 | 存储 | `schedule_rec` 无保留策略，长期运行无限膨胀 |

目标：消除上述缺陷，使框架具备生产可用性。**不改变调度语义**（At-Least-Once、in-flight/Finished 不变量、变更源增量对账、单活 HA 均保持现状）。

## 2. 决策清单（已确认，状态：accepted）

### 2.1 开放接口鉴权（#1）

| 项 | 决策 |
|---|------|
| 覆盖范围 | `/open/**`（作业注册、实例心跳）**
| 校验方式 | `Authorization: Bearer {token}` 或裸 `{token}`，与 `schedule.access-token` 一致 |
| **未配置时** | **一律返回 401（默认拒绝）**，强制显式配置 token 才能放行，杜绝"忘配即裸奔" |
| 失败响应 | HTTP 401 + `Result.fail("unauthorized")`，并 `log.warn` 记录来源 IP 便于发现攻击尝试 |
| `/admin/**` | 本轮不拦（管控后台后续接登录时再补），记为后续项 |
| 落地 | 新增 `OpenApiTokenInterceptor`（`HandlerInterceptor`），仅注册到 `/open/**`；`ScheduleProps` 新增 `access-token` |

### 2.2 回调线程池化（#2）

| 项 | 决策 |
|---|------|
| 现状 | `ScheduleFuture.CALLBACK_EXECUTOR` 静态单线程，回调含 DB 事务 |
| 改造 | 有界线程池：`schedule.callback-threads`（默认 4），有界队列（容量 1024） |
| 拒绝策略 | 队列满时 `RejectedExecutionException` 降级为"在 Netty I/O 线程内直接执行回调"，**不丢回调**（回调是 At-Least-Once 闭环关键步骤） |
| 未来项 | 全量线程池改造为 JDK 21 虚拟线程（天然适配 IO 密集的 DB/Netty 阻塞） |

### 2.3 Worker 心跳 HTTP 超时（#3）

| 项 | 决策 |
|---|------|
| 现状 | `RestClientHelper` 未配置 connect/read timeout，TCP 半开时可能长期阻塞 |
| 超时配置 | `schedule-job.http-connect-timeout`（默认 2000ms）、`schedule-job.http-read-timeout`（默认 3000ms） |
| **约束公式** | **单次 HTTP 尝试超时 ≤ (Admin 租约剔除时间 − 心跳间隔) / Admin 节点数**；例：剔除 30s、心跳 10s（宽限 20s）、5 节点 → 单次尝试须 ≤ 4s，保证最坏轮询全部节点后仍能在过期前完成续租 |
| 落地 | `RestClientHelper` 构建时设置上述超时；`ScheduleJobConfigProps` 新增配置并注释公式 |

### 2.4 Redis 实例存储对账清理（#4）

| 项 | 决策 |
|---|------|
| 现状 | 实例详情键 TTL 过期但服务索引 Set 残留脏键；`list()` 对 `null` 条目调用 `deserialize` → NPE |
| 读路径 | 仅过滤 `null`（`Objects::nonNull`），**不在读路径删索引**（避免与续租竞态） |
| 清理 | **定期清理做"索引/详情对账"**：扫描各 `job:service:{key}` 索引成员，`GET` 实例详情为 null 才删除索引键——以详情为权威，杜绝"删除瞬间恰逢客户端续租成功"的窗口 |
| 自愈 | 即便极端竞态残留，下次心跳 `SADD` 会重建索引，最多影响一个心跳周期的可见性 |

### 2.5 ScheduleRec 落库可靠性（#5）

| 项 | 决策 |
|---|------|
| 失败处理 | `saveBatch` / `update` 失败**不 clear 批次**，退避重试（最多 3 次）；仍失败 `log.error` + 计数器累加（预留监控告警接入点） |
| 队列上限 | `ScheduleRecQueue` 内部队列设容量上限（10000），**打满后丢弃新入队记录并记日志**——不阻塞、不反压派发主链路 |
| 理由 | 普通任务周期循环执行、单次任务重发由 Worker 幂等兜底，`schedule_rec` 属审计/对账载体，尽力落库即可 |

### 2.6 静态资源实例化（#6）

| 项 | 决策 |
|---|------|
| 现状 | `ChannelManager`（静态 EventLoopGroup + 双 Map）、`ScheduleRequestHandler`（静态 Map + 静态清理线程 + `CLEANUP_STARTED` 标志）、`ScheduleFuture`（静态回调线程池） |
| 缺陷 | 容器重启/热部署后清理线程永久失效（`CLEANUP_STARTED` 不重置）；静态 Map 跨上下文残留 |
| 改造 | **彻底实例化**：三者重构为 Spring 单例 Bean（字段从 `static` 移除），由 `NettyLifecycle` 统一管理启动/停止（`stop()` 关闭线程池并清空状态） |
| 依赖关系 | `ChannelManager` / `ScheduleRequestHandler` 由 `ScheduleJobClient` 注入；`ScheduleFuture` 的回调线程池改用注入的线程池 Bean |

### 2.7 可观测性（#7）

| 项 | 决策 |
|---|------|
| 依赖 | 引入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（**豁免 AGENTS.md "不引入新运行时依赖"约束**，此为生产可观测性必需） |
| 指标 | 调度延迟（`take` 到派发）、引擎队列积压数、回调成功/失败计数、变更源消费水印滞后（`maxId - watermark`）、心跳丢弃数、ScheduleRec 落库失败计数 |
| 暴露 | `/actuator/prometheus`（Prometheus 文本格式）+ `/actuator/metrics`、`/actuator/health` |

### 2.8 SmartLifecycle 停机顺序（#8）

| 组件 | phase | 停机顺序 |
|---|------|------|
| `JobScheduler` | `Integer.MAX_VALUE - 10` | 最先停（停派发/对账） |
| `ScheduleRecQueue` | `0`（默认） | 中间停（排空落库） |
| `NettyLifecycle` | `Integer.MIN_VALUE + 10` | 最后停（关连接与回调线程） |
| `ScheduleLeaderElector` | `Integer.MIN_VALUE`（既有） | 最最后停（释放租约锁） |

Spring 停机按 phase **从高到低**；启动按从低到高（`ScheduleLeaderElector` 最先启动保证 `isLeader` 就绪）。

### 2.9 Worker RPC 鉴权（#9）

| 项 | 决策 |
|---|------|
| 现状 | Worker `JobBootstrap` 监听端口无鉴权，`JobInstanceHandler` 按 `jobname` 反射执行任意请求 |
| 改造 | `ScheduleJobRequest` 增加 `token` 字段；Admin 派发时携带 `schedule-job.accessToken`；Worker `JobInstanceHandler` 校验请求 token 与本地 `schedule-job.accessToken` 一致，**不匹配返回失败响应并断开连接**（防暴力尝试） |
| 兼容 | Admin 与 Worker 同版本升级无碍；JSON 编解码对新增字段天然向后兼容（旧字段序列化新增字段=null，校验失败） |

### 2.10 schedule_rec 保留策略（#10）

| 项 | 决策 |
|---|------|
| 配置 | `schedule.rec-retention-days`（默认 7 天） |
| 清理 | 由 `ScheduleRunRecovery` 或独立定时任务每日执行：删除 `complete_time < now - retention` 的**终态记录**（FAIL/SUCCESS）；**RUNNING 不删**（非终态，清扫依赖它判定在途） |

## 3. 依赖变更

| 模块 | 变更 |
|---|---|
| `admin` | 新增 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`（均由 Spring Boot 3.5.6 父 POM 管理版本，无 version 指定） |

## 4. 关键约束

- JDK 21；Spring Boot 3.5.6；Netty 4.1.108.Final；MyBatis-Plus 3.5.7。
- **不改变调度语义**：At-Least-Once、in-flight/Finished 不变量、变更源增量对账、单活 HA、双发窗口 ≤1s 均保持现状。
- 回调与消费不得在 Netty I/O 线程执行（回调降级执行除外——极端压力下宁可占用 I/O 线程也不丢回调）。
- `schedule.ha.enabled=false` 时行为与现状一致。
- HTTP 超时公式（2.3）是硬约束，注释与文档须固化。
- 默认拒绝鉴权（2.1）：`schedule.access-token` 未配置时 `/open/**` 一律 401。
- 本轮允许引入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 两个运行时依赖（豁免 §AGENTS.md 约束），其余不得新增。

## 5. 验收标准（实施轮）

1. **鉴权**：未配置 `schedule.access-token` 时 `/open/job/register` 返回 401；配置后带正确 Bearer 放行、错误 token 401 且 `log.warn` 记录来源 IP；`/admin/**` 行为不变。
2. **回调线程池**：`schedule.callback-threads=4` 生效；并发回调可并行执行；队列满时回调不丢失（降级执行），`ScheduleRec` 终态与 `job_change` 记录正常写入。
3. **HTTP 超时**：`schedule-job.http-connect-timeout` / `http-read-timeout` 生效；Admin 不可达时心跳在超时内快速失败并转移下一个地址。
4. **Redis 存储**：存在过期索引脏键时 `discover` 不抛 NPE；周期清理后脏键被移除；正常心跳实例不受影响。
5. **落库可靠性**：模拟 DB 故障时 `saveBatch` 重试 3 次后仍失败仅记日志不崩溃；队列打满丢弃新记录并记日志，派发主链路不受影响。
6. **实例化**：应用启动/停止无异常；重启后超时清理线程正常重新启动；无静态 Map 跨上下文残留。
7. **可观测性**：`/actuator/prometheus` 可抓取，含调度延迟、队列积压、回调计数、变更源滞后、落库失败计数等指标。
8. **停机顺序**：应用停止时按 JobScheduler → ScheduleRecQueue → NettyLifecycle → ScheduleLeaderElector 顺序执行，日志可见顺序正确。
9. **RPC 鉴权**：`ScheduleJobRequest` 带 token；Worker 收到错误 token 返回失败并断开连接；正确 token 正常执行。
10. **保留策略**：`schedule.rec-retention-days=7` 默认生效，仅清理终态记录，RUNNING 保留。
11. 全量 `mvn test` 通过。

## 6. 风险与回滚

- **鉴权默认拒绝**：升级后未配置 `schedule.access-token` 的存量部署所有 `/open/**` 请求失败 → 升级清单必须包含配置 token；已明确写入验收标准与升级说明。
- **actuator 暴露**：`/actuator/**` 默认对外可见，需配置 `management.endpoints.web.exposure.include` 白名单（默认只暴露 `health`，prometheus/metrics 需显式开启）。
- **RPC 鉴权**：Admin 与 Worker 版本不一致时（新 Admin 带 token / 旧 Worker 不校验）不破坏；反方向（旧 Admin / 新 Worker）请求无 token 会失败 → 升级顺序须先升 Worker 再升 Admin。
- **保留策略误删**：仅删终态记录，RUNNING 保留，最坏影响为审计历史变短，不影响调度正确性。
- 回滚：移除配置与拦截器即可；新增依赖可从 pom 移除。

## 7. 未来项

- **虚拟线程化**：`ScheduleFuture` 回调、`JobScheduler` worker 池、`JobInstanceHandler` 业务池、`ScheduleRecQueue` 消费线程、心跳线程等全量改造为 JDK 21 虚拟线程。
- **`/admin/**` 鉴权**：管控后台接入登录/权限体系后补充。
- **`schedule_rec` 分区表**：体量达百万级时按 `schedule_time` 月分区，滚动删分区替代保留天数清理。
- **指标告警接入**：基于 `/actuator/prometheus` 对接告警平台（变更源滞后、回调失败率、落库失败计数）。
