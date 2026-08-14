# 凭证体系 Spec（按应用身份 + 环境发放）

> 本 Spec 由 grill 会话逐项决策固化而来，配套 `docs/adr/0006-per-application-credentials.md`。
> **本轮只固化决策，代码实施另起一轮**：需独立的迁移方案、升级顺序与回滚预案。
> 术语见 `CONTEXT.md`；线程/依赖/一致性约束见 `AGENTS.md` §5。

## 1. 决策一览

| # | 议题 | 决策 |
| :-- | :--- | :--- |
| 1 | 凭证身份维度 | `(applicationName, env)`——应用身份 + 环境，**非**实例维度 |
| 2 | `applicationName` 来源 | Worker 侧新配置 `schedule-job.application-name`，**强制配置**，为空启动失败 |
| 3 | `env` 来源 | starter 从 Spring `activeProfiles` 取首个非 `test` 值，无 profile 时 `default` |
| 4 | `env` 提取位置 | `ScheduleJobAutoConfiguration`（注入 `Environment`）→ 传入 `ScheduleJobCoreFactory`；core 不依赖 Spring |
| 5 | 凭证生成方 | Admin 预生成，**非** Worker 自助申请（避免 bootstrap 鸡生蛋） |
| 6 | 存储形式 | PBKDF2WithHmacSHA256 + 随机盐，**不可逆**；纯 JDK，不引入依赖 |
| 7 | 明文交付 | 仅在创建/轮换响应中返回一次；遗失只能轮换 |
| 8 | 控制台展示 | 仅 `masked_token`（如 `sj_****a8f2`）脱敏展示 |
| 9 | 有效期 | 默认 90 天，上限 365 天，必须晚于当前时间 |
| 10 | 过期预警 | 剩余 30/7/1 天告警；指标 `job.credential.expiring` |
| 11 | 过期后行为 | `/open/**` 401 `CREDENTIAL_EXPIRED`；停止 RPC 签名与派发；不自动延期、不回退旧版本 |
| 12 | 轮换模式 | 两阶段：`prepare`（建 PENDING）→ 部署 → `activate`（生效 + 旧版立即吊销，**无宽限期**） |
| 13 | 并发控制 | 状态机 CAS（状态条件更新 + 影响行数检查），**无**独立数字锁 |
| 14 | 首次创建 | 无 active 时 `prepare` 直接建 ACTIVE；`create`/`reissue` 合并进 `prepare` |
| 15 | `activate` 就绪校验 | 默认要求该身份所有在线实例已用 pending 心跳成功；`force=true` 可跳过但须填原因 |
| 16 | 实例版本归属 | 由**服务端按鉴权结果**写入 `instance.credential_version`，不信任 Worker 自报 |
| 17 | `cancel` | 只取消 PENDING，不影响 ACTIVE；只用 `pendingVersion` 做 CAS |
| 18 | 过期 PENDING | 不自动清理、不被新 prepare 覆盖，必须人工 cancel 并填原因 |
| 19 | 紧急吊销 | `revoke` 立即失效；无可用版本时 Fail-Closed（不回退全局令牌） |
| 20 | 审计表 | 无独立 history 表，由 `credential_version` 兼任版本 + 历史 + 审计 |
| 21 | 操作人来源 | 强制 `UserSessionContext.getUserName()`；请求体**不含** applicant |
| 22 | 权限模型 | 本阶段仅要求登录，不引入 RBAC |
| 23 | RPC 鉴权 | 以 `token_hash` 为密钥的 **HMAC-SHA256 请求签名**，摘要**不上网** |
| 24 | 重放防护 | ±30s 时间窗 + `requestId` 当 nonce 的进程内去重集 |
| 25 | Worker 派生缓存 | 按 `(version, salt, iterations)` 缓存 derivedKey，上限 4、淘汰最小版本号，**派生在业务线程池** |
| 26 | Admin 摘要缓存 | 写路径 `afterCommit` 主动失效 + 60s TTL 自愈 + `credential_change` 轮询跨节点广播 |
| 27 | 变更记录内容 | **仅失效信号**，不携带 `token_hash`/`salt` |
| 28 | 兼容策略 | 不兼容升级：删除 `schedule.access-token` 与 `schedule-job.group.name`，不留兼容期（项目未上线） |

## 2. 数据模型

### 2.1 `credential`（凭证身份）

`(application_name, env)` 唯一键 + `active_version` / `pending_version` 两个状态指针。指针即业务状态，`NULL` 表示无该状态版本。

### 2.2 `credential_version`（版本 + 历史 + 审计）

每版一行：`version`、`status`（PENDING/ACTIVE/REVOKED/CANCELED）、`token_hash`、`salt`、`iterations`、`masked_token`、`expire_time`，以及全套审计列（`prepared_by`/`create_time`、`activated_by`/`activate_time`/`forced_activation`/`activation_reason`、`revoked_by`/`revoke_time`/`revoke_reason`、`canceled_by`/`cancel_time`/`cancel_reason`）。

唯一键 `(credential_id, version)`。状态**单向流转**，版本号不复用：

```
PENDING ──activate──> ACTIVE ──下一版 activate / revoke──> REVOKED
   │
   └────cancel──────> CANCELED
```

**进入 REVOKED / CANCELED 后清除 `token_hash` 与 `salt`**，只留脱敏值与审计信息，缩小密码材料留存面。

### 2.3 `credential_change`（变更源）

`application_name` / `env` / `change_type`(1=PREPARE 2=ACTIVATE 3=CANCEL 4=REVOKE) / `operator` / `create_time`，与凭证写入**同事务**。只作缓存失效信号。

### 2.4 `instance` 表新增列

`application_name`、`env`、`credential_version`（服务端按鉴权结果写入，供 `activate` 就绪校验与派发选版）。

## 3. 鉴权协议

### 3.1 Worker → Admin（`/open/**`）

```http
Authorization: Bearer <明文 token>
X-Job-Group: payment
X-Job-Env:   prod
```

拦截器按 Header 身份查 active/pending 两版摘要，用各自 `salt`/`iterations` 派生后常量时间比较；命中即放行并记录该实例所用版本。Controller **二次校验** Header 身份与 Body 中 `applicationName`/`env` 一致，防止持合法凭证注册到其他身份。

> 身份走 Header 而非 Body：拦截器读 Body 会消费请求流，需额外缓存重放；且 token 进入 DTO 会扩大日志/序列化泄露面。

### 3.2 Admin → Worker（RPC 派发）

`ScheduleJobRequest` **删除 `token`**，新增 `credentialVersion` / `salt` / `iterations` / `timestamp` / `signature`（nonce 复用既有 `requestId`）。

```
Admin:  signature = HMAC-SHA256(token_hash, canonical(requestId, jobname, executeParam, timestamp, nonce))
Worker: derivedKey = PBKDF2(本地明文 token, salt, iterations, 256)
        expected   = HMAC-SHA256(derivedKey, canonical(...))
        constantTimeEquals(expected, signature)
```

Worker 侧拒绝条件：时间戳超 ±30s、`requestId` 已见过、`credentialVersion` 与本地不符、签名不匹配。

**这是认证（MAC）而非加密**：`jobname`/`executeParam` 在 Netty 上仍是明文，可被读取但不可篡改、不可伪造。**禁止**据此认为执行参数具备机密性——机密性需给通道上 TLS，属独立议题。

### 3.3 重放去重

`requestId → 到达时间`，TTL = 2×时间窗（60s），懒清理；容量上限 10 万（超限先清过期，仍超则拒绝新请求，宁可短暂拒合法流量也不 OOM）。去重在**验签阶段（I/O 线程）**完成，重放请求不进业务线程池。

`requestId` 可直接当 nonce：R2 语义每次调度唯一，且**合法重试都会换新 ID**（REQUEUE 重新派发、cron 下个火点），不会误杀。

## 4. 缓存与一致性

| 层 | 键 | 失效机制 |
| :--- | :--- | :--- |
| Worker derivedKey | `(version, salt, iterations)` | 上限 4，淘汰最小版本号 |
| Admin 摘要 | `(applicationName, env)` | `afterCommit` 主动失效 + 60s TTL + 变更源轮询 |

**三条硬性约束**：

1. **失效必须挂 `afterCommit`，禁止在事务内 evict**——否则并发读会在提交前回填旧值，缓存反而永久陈旧。
2. **`credential_change` 轮询线程有意不做 leader 门控**：`/open/**` 校验在所有节点执行，standby 节点缓存同样必须刷新。照抄 `QueueReconciler`/`ScheduleRunRecovery` 的 `isLeader` 前置判断会造成 standby 永不刷新。水印为**进程内独立**，启动置 `max(id)` 不回放。反之其**记录清理**（按 `create_time` 保留 1h）**应当**门控，属纯 housekeeping。
3. **凭证过期判定依据版本行 `expire_time`**，不得用缓存 TTL 代替——两者是不同时间概念，混用会让过期最多延迟 TTL 秒生效。

**校验失败强制重载**：校验不通过时先绕过缓存 reload 一次再判定，使「新凭证生效」方向近实时、不误拒（该方向的陈旧会导致实例掉线）。「旧凭证失效」方向的陈旧只能由 TTL 与变更源封顶（约 1s）。

## 5. 已知限制（勿误判为已解决）

1. **拖库可伪造 RPC**：`token_hash` 兼任「注册路径存储保护」与「RPC HMAC 密钥」。DB 泄露时前者仍安全（注册需出示明文），后者失守——可伪造调度请求触发任意已注册任务。该缺口在对称方案下**无法弥补**（Worker 必须能独立算出同一密钥，任何 pepper 都得让 Worker 知晓）。消除只能改用 Ed25519 非对称签名（Admin 私钥签、Worker 存公钥），列为后续可选增强。
2. **跨 Worker 重放**：去重集是进程内的，可挡同连接重放，挡不住把截获请求定向灌给同应用另一 Worker。跨节点去重需共享存储（Redis `SETNX`），每请求多一次 RTT，故不默认启用。
3. **轮换需滚动重启**：凭证经环境变量分发，Worker 无法运行时热更新，故必须两阶段。`revoke` 后到新凭证部署完成之间的停机窗口由部署速度决定，与新版本初始状态（ACTIVE 或 PENDING）无关。

## 6. 实施影响面清单

- **新增**：3 张表、`credential` 相关实体/Mapper/Rep/Service、4 个管理接口（`prepare`/`activate`/`cancel`/`revoke`）、凭证缓存组件、变更源轮询组件、PBKDF2 与 HMAC 工具（common）。
- **修改**：`OpenApiTokenInterceptor`、两个 `/open/**` Controller、`ScheduleServiceTemplate`（派发签名）、`JobInstanceHandler`（验签 + 去重，替代 `tokenValid`）、`ScheduleJobRequest`、`JobInstance`、`Instance`、`ScheduleJobConfigProps`、`ScheduleJobAutoConfiguration`、`ScheduleJobCoreFactory`、`ScheduleJobAnnotationProcessor`、samples 各配置、`docs/sql/schema.sql`。
- **删除**：`schedule.access-token`、`schedule-job.group.name`、`ScheduleJobRequest.token`。
- **文档同步**：`AGENTS.md` §0.4（配置键增删）、§5.5（升级顺序不再基于全局令牌对称）、§4（表清单）、`CONTEXT.md`（新增 Credential / Application Identity 术语）。

## 7. 验收标准

1. 未配置 `schedule-job.application-name` 的 Worker 启动失败并给出明确错误。
2. `/open/**` 缺 Header 身份、token 错误、凭证过期、身份与 Body 不一致，四种情况均 401 且错误码可区分。
3. 两阶段轮换全过程零调度中断：prepare 后新旧 token 均可注册；activate 后旧 token 立即失效。
4. `activate` 在仍有旧版本在线实例时返回 `CREDENTIAL_NOT_READY`；`force=true` 且填原因时成功并记录 `forced_activation`。
5. `revoke` 后该身份注册/心跳/派发全部拒绝，且不回退全局令牌。
6. 同一身份并发 prepare 只有一个成功，另一个返回 `CREDENTIAL_PENDING_EXISTS`；并发 cancel/activate 同一 pending 只有一个生效。
7. RPC 签名篡改任一字段（jobname/executeParam/timestamp）后 Worker 拒绝执行。
8. 同一 RPC 请求重放：第二次被拒且业务方法未被反射调用。
9. 超 ±30s 时间窗的请求被拒。
10. Worker 侧同一版本的 PBKDF2 只派生一次（后续请求无派生开销），且派生不在 Netty I/O 线程。
11. `revoke` 后其他 Admin 节点在约 1s 内停止接受旧 token（变更源生效）。
12. 被吊销/取消版本的 `token_hash` 与 `salt` 已清空，`masked_token` 与审计信息保留。
13. 有效期缺省为 90 天；传入超 365 天或早于当前时间被拒。
14. 凭证管理接口在未登录时拒绝，且 applicant 不可由请求体伪造。
