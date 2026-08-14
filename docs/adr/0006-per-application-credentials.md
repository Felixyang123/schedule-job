# 0006-per-application-credentials

Status: accepted（决策已固化，代码实施另起一轮）

Worker 与 Admin 之间原先共用单一全局令牌 `schedule.access-token`：`/open/**`（作业注册、实例心跳）以 Bearer Token 校验它，Admin→Worker 的 RPC 派发把同一明文塞进 `ScheduleJobRequest.token` 由 Worker 比对。该模型有三个问题——所有应用共享同一凭证（任一应用泄露即全局失守）、令牌以明文存在于配置与协议中且无法轮换、Worker 无法识别派发方身份。我们决定改为**按「应用身份 + 环境」发放凭证**：凭证键为 `(applicationName, env)`，其中 `applicationName` 来自 Worker 侧新配置项 `schedule-job.application-name`（强制配置，与负载均衡用的 `group.enabled` 解耦），`env` 由 starter 从 Spring `activeProfiles` 首个非 test 值提取（无 profile 时为 `default`）。凭证由 Admin 预生成，明文仅在创建/轮换响应中返回一次，数据库只存 **PBKDF2WithHmacSHA256 + 随机盐**派生的不可逆摘要（纯 JDK 实现，不引入 `spring-security-crypto`，满足 AGENTS.md §5.1）；控制台按 `masked_token` 脱敏展示。`/open/**` 改由 Header `X-Job-Group` + `X-Job-Env` 携带身份、`Authorization: Bearer` 携带明文，拦截器查表校验摘要，Controller 二次校验 Header 与 Body 身份一致。Admin→Worker RPC **不再传输任何可复用凭证**：以数据库中的 PBKDF2 摘要作为 HMAC-SHA256 密钥对规范化请求签名，Worker 用本地明文派生同一密钥验签，请求另携带 `credentialVersion / salt / iterations / timestamp / nonce(=requestId)`，Worker 按 ±30s 时间窗 + `requestId` 去重集拒绝重放。轮换采用两阶段（`prepare` 生成 PENDING → 部署确认 → `activate` 使其 ACTIVE 并立即吊销旧版本，无宽限期），并发以状态机 CAS（`pendingVersion` / `activeVersion` + 状态条件更新 + 影响行数检查）控制，不设独立数字锁。本决策为**不兼容升级**：全局 `schedule.access-token` 与 `schedule-job.group.name` 一并删除，旧 Worker 无法接入新 Admin（项目尚未上线，不保留兼容期）。

## 考虑过的方案

- **凭证按实例（`host:port`）发放**：容器环境下 IP 漂移、Pod 重建即失效，且与「凭证经环境变量随应用分发」的交付方式自相矛盾（每次扩容都需人工申请）；同一应用的 Worker 跑同一份代码，实例级隔离安全收益极低。否决，改为应用身份维度。
- **AES 可逆加密存储凭证**：可支持控制台回显明文，但需管理主密钥（密钥若放配置则与明文存储等价），且泄露一次即全量沦陷。而「脱敏展示」只需 `masked_token`，不需要还原明文。否决。
- **RPC 直接携带 `token_hash` 做相等比较**：摘要在网络上等价于 Bearer Token，截获一次即可无限重放并任意篡改参数。否决，改为以摘要为密钥的 HMAC。
- **Ed25519 非对称签名**（Admin 私钥签、Worker 存公钥）：可做到「拖库也无法伪造 RPC」，安全性严格优于 HMAC；本轮未采用（见「后果」中的已知限制），保留为后续可选增强。
- **轮换单阶段立即替换**：实现最简，但 Worker 凭证来自环境变量、需滚动重启才能生效，轮换瞬间会造成存量 Worker 全量鉴权失败。否决，改为两阶段。
- **`activate` 后给旧凭证留宽限期**：与「凭证有过期时间」的语义冲突（未过期的旧凭证长期有效则过期时间形同虚设），且需引入 `is_active` 状态机并去掉唯一键。否决——过渡态由 prepare→activate 之间的双版本并行承担，activate 后不留窗口。
- **凭证缓存仅靠短 TTL 跨节点收敛**：`/open/**` 校验在所有 Admin 节点执行（不受 leader 门控），进程内失效只覆盖本节点，纯 TTL 会给吊销留下窗口。否决，改为变更源广播（见下）。
- **变更记录携带新摘要直接回填缓存**：省一次查询，但要把 `token_hash`/`salt` 再存一份到变更表，与「吊销后清除密码材料」相矛盾，且需处理乱序回退。否决，变更记录只作失效信号。
- **`credential_history` 独立审计表**：与版本表字段高度重复（版本表已含各版申请人、时间、状态流转），维护两套历史反而易不一致。否决，由 `credential_version` 兼任审计。
- **凭证管理接口引入 RBAC**：当前项目无角色与授权模型，本轮引入会显著扩大范围。否决，先要求登录并从 `UserSessionContext` 取操作人，权限细化另议。

## 后果

- 新增三张表：`credential`（身份 + `active_version`/`pending_version` 指针）、`credential_version`（每版摘要/盐/迭代次数/脱敏值/有效期/申请人/激活与吊销审计，兼任轮换历史）、`credential_change`（凭证变更源，仅作缓存失效信号，字段为 `application_name`/`env`/`change_type`/`operator`）。`instance` 表新增 `application_name`、`env`、`credential_version` 三列。
- `JobInstance` 新增 `applicationName`、`env`；`ScheduleJobRequest` 删除 `token`，新增 `credentialVersion`、`salt`、`iterations`、`timestamp`、`signature`（nonce 复用既有 `requestId`，不新增字段）。
- 状态机严格化：`prepare` 在无 active 时直接建 ACTIVE（首次创建与吊销后恢复同一路径，`create`/`reissue` 合并掉）、有 active 时建 PENDING、已有 pending 时中断返回 `CREDENTIAL_PENDING_EXISTS`；`cancel` 只取消 PENDING 且不影响 ACTIVE；过期 PENDING 不自动清理、必须人工 cancel 并填原因。四个管理接口：`prepare` / `activate` / `cancel` / `revoke`。
- `activate` 默认校验部署就绪度（该身份所有在线实例均已用 pending 版本心跳成功），未就绪返回 `CREDENTIAL_NOT_READY`；`force=true` 可跳过但必须填写原因并记入审计。实例使用的版本由**服务端按鉴权结果**写入，不信任 Worker 自报。
- 紧急吊销 Fail-Closed：`revoke` 后该身份无可用凭证时，`/open/**` 全部 401、派发停止，不回退全局令牌；恢复须走 `prepare`。被吊销/取消版本清除 `token_hash` 与 `salt`，只留脱敏值与审计信息。
- 有效期默认 90 天、上限 365 天；30/7/1 天预警，指标 `job.credential.expiring`；过期判定依据版本行 `expire_time`，**不得**用缓存 TTL 代替。
- 缓存三层协同：Worker 侧按 `(version, salt, iterations)` 缓存 PBKDF2 派生密钥（上限 4、淘汰最小版本号，**派生在业务线程池执行**，命中后单次 HMAC，避免在 Netty I/O 线程做耗时计算，AGENTS.md §5.2）；Admin 侧按 `(applicationName, env)` 缓存摘要，写路径提交后主动失效（须挂 `afterCommit`，**不得在事务内失效**，否则并发读会回填未提交的旧值）+ 60s TTL 自愈 + `credential_change` 轮询（约 1s）跨节点广播；校验失败时强制绕过缓存重载一次再判定，使「新凭证生效」方向近实时不误拒。
- `credential_change` 的消费者是**每个 Admin 节点**（水印进程内独立、启动置 `max(id)` 不回放），因此该轮询线程**有意不做 leader 门控**——照抄 `QueueReconciler`/`ScheduleRunRecovery` 的 `isLeader` 前置判断会导致 Standby 节点缓存永不刷新；反之其记录清理（按 `create_time` 保留 1h）**应当**门控，属纯 housekeeping。此约束须在类注释中显式声明。
- 需同步修改：`OpenApiTokenInterceptor`、两个 `/open/**` Controller、`ScheduleServiceTemplate`（派发侧签名）、`JobInstanceHandler`（验签 + 去重，替代原 `tokenValid`）、`ScheduleJobConfigProps`/`ScheduleJobAutoConfiguration`/`ScheduleJobCoreFactory`/`ScheduleJobAnnotationProcessor`（身份与 env 注入链）、`samples` 各示例配置、`docs/sql/schema.sql`，以及 AGENTS.md §5.5（升级顺序不再基于全局令牌对称）与 §0.4（配置键增删）。
- **已知限制（必须记录，勿误判为已解决）**：其一，`token_hash` 同时充当「注册路径的存储保护」与「RPC 的 HMAC 密钥」，数据库被拖库时前者仍安全（注册需出示明文）但后者失守——攻击者可伪造调度请求触发任意已注册任务；该缺口在对称方案下无法弥补（Worker 必须能独立算出同一密钥，任何 pepper 都得让 Worker 知晓），要消除只能改用 Ed25519 非对称签名。其二，重放去重集是 Worker 进程内的，可挡同连接重放，但挡不住把截获请求定向灌给同应用另一 Worker 的跨节点重放；跨节点去重需共享存储（如 Redis `SETNX`），每请求多一次 RTT，故不默认启用。
