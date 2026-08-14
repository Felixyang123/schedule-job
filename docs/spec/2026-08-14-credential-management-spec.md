# 凭证管理 Spec（登录 / 管理接口 / 变更源轮询 / 过期预警）

> 承接 `docs/adr/0006-per-application-credentials.md`（accepted）与 `docs/spec/2026-08-11-credential-spec.md`。
> 2026-08-11 Spec 固化的是「凭证体系全量设计决策」；2026-08-14 实施轮（`2026-08-14-credential-implementation.md`）
> 已落地鉴权 + 签名 + 种子初始化，**管理面（登录、prepare/activate/cancel/revoke、变更源轮询、过期预警）留待本轮**。
> 本 Spec 补齐管理面的决策，为实施 Plan 提供权威依据。
> 已实施的现状以代码为准；本 Spec 中标注「已实施」的条目仅作上下文，不重复设计。

## 1. 现状与范围

### 1.1 已实施（2026-08-14 实施轮，不再讨论）

- `/open/**` 按身份鉴权（Header 身份 + Bearer 明文 + active/pending 双版摘要校验 + 强制重载）。
- Admin→Worker RPC HMAC 签名（token_hash 作密钥）+ Worker 验签/重放去重。
- Worker 身份注入链（`application-name` 强制 + env 提取）。
- 种子初始化 `schedule.credential-seed`（幂等建 ACTIVE v1，Fail-Closed）。
- 三张表（credential / credential_version / credential_change）+ instance 三列，实体/Rep/Service 骨架。

### 1.2 本轮范围（后续任务）

| # | 任务 | 说明 |
| :-- | :--- | :--- |
| 1 | 登录与会话机制 | 配置化单用户，`UserSessionContext` 获得写入方（此前恒为 null） |
| 2 | 凭证管理接口 | `prepare` / `activate` / `cancel` / `revoke`，CAS 并发控制 |
| 3 | `credential_change` 轮询与清理 | 跨节点缓存失效广播（不做 leader 门控）+ 记录清理（门控） |
| 4 | 过期预警指标 | `job.credential.expiring` |
| 5 | Ed25519 增强 | **仅设计记录，本阶段不实施**（用户拍板） |

## 2. 决策一览（本轮新增）

| # | 议题 | 决策 |
| :-- | :--- | :--- |
| 1 | 登录形态 | 配置化单用户：`schedule.admin.username` / `schedule.admin.password`；不建用户表（无 RBAC 需求，ADR 决策 #22） |
| 2 | 密码校验 | 常量时间比较（`MessageDigest.isEqual`），配置即事实源（与凭证 seed 同一交付模型） |
| 3 | Fail-Closed | 未配置 `schedule.admin.password` 时 `/admin/**` 一律 401，启动 WARN |
| 4 | 会话载体 | 进程内 `ConcurrentHashMap<token, Session>`：SecureRandom 32B hex token，TTL 30 分钟滑动续期；多 Admin 节点会话互不可见（本阶段接受，管理面通常单点访问） |
| 5 | 会话注入 | 新 `AdminAuthInterceptor`：`/admin/**` 校验 `Authorization: Bearer {token}`，命中写入 `UserSessionContext`（`setUserSession`），`afterCompletion` 清理；`/admin/auth/login`、`/admin/auth/logout` 豁免 |
| 6 | 登录保护范围 | **全部 `/admin/**`**（含既有 JobController 等）——`JobService` 已用 `UserSessionContext.getUserName()` 写 operator，登录落地后 operator 才有真实值 |
| 7 | 操作人来源 | 管理接口一律从 `UserSessionContext.getUserName()` 取，请求体不含 applicant（ADR 决策 #21） |
| 8 | 接口命名 | `/admin/credential/prepare` / `activate` / `cancel` / `revoke`，Body JSON，统一 `Result<T>` 包装 |
| 9 | 明文返回 | 仅 `prepare` 响应返回一次明文（`plaintext` 字段）；后续查询只出 `maskedToken`（ADR 决策 #7） |
| 10 | 版本号 | `prepare` 时取该身份 `max(version)+1`（恢复路径不冲突唯一键 `(credential_id, version)`，复用已实施逻辑） |
| 11 | 并发控制 | 状态机 CAS：条件更新 + 影响行数检查，不设独立数字锁（ADR 决策 #13） |
| 12 | 变更源 | 管理写路径与凭证写入同事务落 `credential_change`（change_type 1~4）；`afterCommit` 后 `invalidate(app, env)`（Spec 2026-08-11 §4 硬性约束 1） |
| 13 | 轮询线程 | 每个 Admin 节点独立运行，**不做 leader 门控**（standby 也校验 `/open/**`）；水印进程内独立、启动置 `max(id)` 不回放；1s 轮询 LIMIT 100 |
| 14 | 记录清理 | 按 `create_time` 保留 1h 删除，**leader 门控**（纯 housekeeping）；每 300 次扫描触发一次 |
| 15 | 过期预警 | `job.credential.expiring` Gauge，labels `{application, env, version}`，值 = ACTIVE 版本剩余天数；≤30 天上报，其余不设；Prometheus 告警规则层处理 30/7/1 天阈值 |
| 16 | 过期判定 | 依据版本行 `expire_time`（ADR 决策 #10 语义），与缓存 TTL 无关 |
| 17 | Ed25519 | 仅 spec 记录设计（§8），不实施：无拖库威胁模型、验证成本高、需双算法过渡 |

## 3. 登录与会话机制

### 3.1 配置

```yaml
schedule:
  admin:
    username: admin        # 默认 admin；空值按不配置处理
    password: "xxxxx"      # 强制配置，未配置时 /admin/** 一律 401（Fail-Closed）
```

`ScheduleProps` 新增 `adminUsername` / `adminPassword` 两字段（kebab-case `schedule.admin.username` / `schedule.admin.password`）。
密码为明文配置（与 `schedule.credential-seed` 同一交付模型：明文经环境变量/配置分发，不落库），
校验时对请求密码做常量时间比较，不引入 PBKDF2（无摘要可对）。

### 3.2 接口

```
POST /admin/auth/login    body: {username, password}
  成功 → 200 {code:1, data:{token, username, expireTime}}
  失败 → 401 {code:-1, message:"invalid credentials"}

POST /admin/auth/logout   header: Authorization: Bearer {token}
  成功 → 200 移除会话
```

- 登录成功：`SecureRandom` 32 字节 hex token → `sessions.put(token, new Session(username, expireAt=now+30min))`。
- 滑动续期：`AdminAuthInterceptor` 每次命中校验时刷新 `expireAt`（上限封顶，如续期后仍 ≤ now+30min）。
- 会话表需考虑清理：懒清理（每次 put 前扫描过期项）即可，规模小（管理面）。

### 3.3 `AdminAuthInterceptor`（新增）

- 注册路径：`/admin/**`（`WebMvcConfig` 注册），豁免 `/admin/auth/login`、`/admin/auth/logout`。
- 流程：`preHandle`：取 `Authorization` 头 → 剥 `Bearer ` 前缀（复用 `OpenApiTokenInterceptor.normalize` 的剥离逻辑，抽公共静态方法）→ 查会话表 → 未命中 401；命中则 `UserSessionContext.setUserSession(...)` 并续期。
- `afterCompletion`：`UserSessionContext.clear()`（防线程池复用串线，`UserSessionContext` 类注释已有此要求）。
- 未配置 `schedule.admin.password`：直接 401（Fail-Closed），启动时 WARN。

### 3.4 与 `UserSessionContext` 的关系

`common/session/UserSessionContext` 已存在（`InheritableThreadLocal`），本轮补上**唯一的写入方** `AdminAuthInterceptor`。
`JobService` / 新管理服务里 `UserSessionContext.getUserName()` 由此获得真实值。

## 4. 凭证管理接口契约

### 4.1 通用约定

- 基路径 `/admin/credential`，全部要求登录（AdminAuthInterceptor 兜底）。
- 请求/响应统一 `Result<T>` 包装；业务失败 `code="-1"`、`message` 为机器可读错误码（见 §4.6）。
- 所有写路径：**同一事务内**完成「版本行流转 + 身份指针更新 + `credential_change` 落记录」，
  事务提交后（`TransactionSynchronization.afterCommit`）调用 `CredentialService.invalidate(app, env)`。
  禁止在事务内 evict（Spec 2026-08-11 §4 硬性约束 1）。

### 4.2 `POST /admin/credential/prepare` — 创建 / 轮换准备

```json
请求: { "applicationName": "payment", "env": "prod", "expireDays": 90 }
响应: { "applicationName": "payment", "env": "prod", "version": 2,
        "maskedToken": "abc****def1", "plaintext": "sj_xxxxx", "expireTime": "..." }
```

行为（ADR 决策 #14 合并 create/reissue 进 prepare）：

| 现状 | 行为 |
| :--- | :--- |
| 身份不存在 | 建身份 + **直接建 ACTIVE v1**（首次创建） |
| 有身份、有 active、无 pending | 建 **PENDING** 版本（轮换过渡），返回明文 |
| 有 pending | 409 `CREDENTIAL_PENDING_EXISTS`（必须先 cancel） |
| 身份存在但 active 为 null（吊销后恢复） | 建 **ACTIVE** 新版本（恢复路径） |

- `expireDays` 缺省 90，>365 或 ≤0 → 400 `CREDENTIAL_INVALID_EXPIRY`。
- 版本号 = `max(version)+1`。
- 新版本审计：`prepared_by` = 登录用户名、`create_time` = now；`expire_time` = now + expireDays。
- CAS：插入版本后 `UPDATE credential SET pending_version=? WHERE id=? AND pending_version IS NULL`，
  影响行数 0 → 并发 prepare 已建 pending → 回滚，返回 `CREDENTIAL_PENDING_EXISTS`。
  （无 active 的恢复路径：`UPDATE credential SET active_version=? WHERE id=? AND active_version IS NULL`，同款 CAS。）
- 写 `credential_change`（change_type=PREPARE）。

### 4.3 `POST /admin/credential/activate` — 轮换生效

```json
请求: { "applicationName": "payment", "env": "prod", "force": false, "reason": "部署完成" }
响应: { "applicationName": "payment", "env": "prod", "activatedVersion": 2, "revokedVersion": 1 }
```

行为（ADR 决策 #12/#15/#17）：

1. 无 pending 版本 → 400 `CREDENTIAL_NO_PENDING`。
2. **部署就绪校验**（默认）：查询该身份所有在线实例
   （`instance` 表 `application_name=? AND env=? AND status=ONLINE`，在线判定复用现有 `status`/`expireTime` 语义），
   全部 `credential_version = pendingVersion` 才就绪；无在线实例视为就绪。
   未就绪 → 409 `CREDENTIAL_NOT_READY`。
   `force=true` 跳过校验，但 **`reason` 必填**（缺 → 400 `CREDENTIAL_REASON_REQUIRED`），并记 `forced_activation=true`。
3. CAS 序列（同事务，逐个校验影响行数）：
   - `UPDATE credential_version SET status=ACTIVE, activated_by=?, activate_time=?, forced_activation=?, activation_reason=? WHERE credential_id=? AND version=? AND status=PENDING` → 0 行：已被 cancel → `CREDENTIAL_VERSION_MISMATCH`。
   - 旧 ACTIVE 版本：`UPDATE credential_version SET status=REVOKED, revoked_by=?, revoke_time=?, revoke_reason=? WHERE credential_id=? AND version=? AND status=ACTIVE`（无旧版则跳过）。
   - 指针：`UPDATE credential SET active_version=?, pending_version=NULL WHERE id=? AND pending_version=?` → 0 行：并发冲突 → `CREDENTIAL_VERSION_MISMATCH`。
   - 被 REVOKED 的版本**清空 `token_hash` 与 `salt`**（ADR 决策：终态清密码材料）。
4. 写 `credential_change`（change_type=ACTIVATE）。

### 4.4 `POST /admin/credential/cancel` — 取消待激活

```json
请求: { "applicationName": "payment", "env": "prod", "reason": "部署回滚" }
响应: { "applicationName": "payment", "env": "prod", "canceledVersion": 2 }
```

- 只取消 PENDING，不影响 ACTIVE（ADR 决策 #17）。
- `reason` 必填（缺 → 400 `CREDENTIAL_REASON_REQUIRED`）。
- CAS：`UPDATE credential_version SET status=CANCELED, canceled_by=?, cancel_time=?, cancel_reason=? WHERE credential_id=? AND version=? AND status=PENDING` → 0 行：无 pending 或已被处理 → `CREDENTIAL_NO_PENDING`。
- 指针：`UPDATE credential SET pending_version=NULL WHERE id=? AND pending_version=?`。
- 被 CANCELED 版本清空 `token_hash` / `salt`。
- 写 `credential_change`（change_type=CANCEL）。
- **过期 PENDING 不自动清理**，必须人工 cancel 并填原因（ADR 决策 #18）。

### 4.5 `POST /admin/credential/revoke` — 紧急吊销

```json
请求: { "applicationName": "payment", "env": "prod", "reason": "疑似泄露" }
响应: { "applicationName": "payment", "env": "prod", "revokedVersion": 1 }
```

- ACTIVE → REVOKED，`active_version` 置 NULL → 该身份**无可用版本**，Fail-Closed：`/open/**` 一律 401、派发停止，不回退（ADR 决策 #19）。
- `reason` 必填。
- CAS：`UPDATE credential_version SET status=REVOKED, revoked_by=?, revoke_time=?, revoke_reason=? WHERE credential_id=? AND version=? AND status=ACTIVE` → 0 行：无 active → `CREDENTIAL_NO_ACTIVE`。
- 指针：`UPDATE credential SET active_version=NULL WHERE id=? AND active_version=?`。
- 清空该版本 `token_hash` / `salt`。
- 恢复路径 = 重新 `prepare`（无 active 时直接建 ACTIVE）。
- 写 `credential_change`（change_type=REVOKE）。

### 4.6 错误码清单（`Result.message`）

| 错误码 | 场景 |
| :--- | :--- |
| `CREDENTIAL_PENDING_EXISTS` | prepare 时已有 pending |
| `CREDENTIAL_NO_PENDING` | activate/cancel 时无 pending |
| `CREDENTIAL_NO_ACTIVE` | revoke 时无 active |
| `CREDENTIAL_NOT_READY` | activate 部署就绪校验未过且非 force |
| `CREDENTIAL_VERSION_MISMATCH` | CAS 条件更新影响行数 0（并发冲突或状态已变） |
| `CREDENTIAL_INVALID_EXPIRY` | expireDays 缺省非法（>365 / ≤0） |
| `CREDENTIAL_REASON_REQUIRED` | force 激活 / cancel / revoke 缺 reason |
| `CREDENTIAL_UNKNOWN` | 身份不存在（与 /open/** 同码） |
| `CREDENTIAL_AUTH_REQUIRED` | 未登录（AdminAuthInterceptor 401） |

> 管理接口错误码与 `/open/**` 既有错误码（MISSING_CREDENTIAL_IDENTITY / CREDENTIAL_INVALID / CREDENTIAL_EXPIRED / CREDENTIAL_IDENTITY_MISMATCH）并存，互不复用。

## 5. `credential_change` 轮询与清理（CredentialChangePoller，新增）

### 5.1 消费（所有节点）

- 生命周期：`SmartLifecycle` 组件（参照 `ScheduleRecQueue` 模式），启动后台单线程。
- 不做 leader 门控——`/open/**` 校验在所有节点执行，standby 缓存同样必须刷新。
  照抄 `QueueReconciler` 的 `isLeader` 前置判断会造成 standby 永不刷新。
  **此约束必须在类注释中显式声明**（ADR-0006 后果段原文要求）。
- 循环：1s 轮询 `SELECT * FROM credential_change WHERE id > ? ORDER BY id LIMIT 100`；
  逐条 `CredentialService.invalidate(app, env)`；`watermark` 推进到批次最大 id。
- 水印进程内独立，**启动置 `max(id)`**（不回放历史）。
- 单条消费失败：WARN + 跳过，不阻断整批（参照 `QueueReconciler.applyChange` 隔离语义）。

### 5.2 清理（仅 leader）

- 每 300 次扫描（≈5min）触发一次：`DELETE FROM credential_change WHERE create_time < now() - 1h`。
- **leader 门控**（纯 housekeeping，不门控会造成 standby 重复删，无正确性问题但无意义）。
- 不清理会导致表无限增长（每轮换一次一条），保留 1h 足够支撑跨节点收敛。

## 6. 过期预警指标（CredentialExpiryMonitor，新增）

### 6.1 指标设计

```
job.credential.expiring{application="payment", env="prod", version="2"} = 剩余天数(可负)
```

- 类型：Gauge；前缀 `job.` 与现有指标一致（`MetricsRegistry`）。
- 上报条件：ACTIVE 版本剩余天数 ≤ 30 时设值（= ceil((expireTime - now) / 86400000)）；> 30 不设。
- 判定依据版本行 `expire_time`，**不得**用缓存 TTL 代替（Spec 2026-08-11 §4 硬性约束 3）。
- 30/7/1 天阈值告警由 Prometheus 告警规则消费该指标实现，代码不内置阈值逻辑（只报剩余天数）。

### 6.2 采集

- 常驻线程 60s 扫描一次：查全部 ACTIVE 版本（join credential 取 app/env）。
- leader 门控上报（多节点重复上报同值无意义）；扫描本身不门控亦可，建议整体门控简化。

## 7. 缓存与一致性（承接 2026-08-11 §4）

| 层 | 键 | 失效机制（本轮补齐） |
| :--- | :--- | :--- |
| Admin 摘要缓存 | `(applicationName, env)` | 管理写路径 `afterCommit` 主动失效 + **变更源轮询（约 1s）跨节点广播** + 60s TTL 自愈 |

- 校验失败强制重载（`lookupForced`）已实施，语义不变。
- 三条硬性约束（afterCommit 失效、轮询不做 leader 门控、过期按 expire_time）本轮全部落地。

## 8. Ed25519 非对称签名（设计记录，**本阶段不实施**）

> 用户拍板：仅记录设计，不实施。消除「拖库可伪造 RPC」这一对称方案固有局限（Spec 2026-08-11 §5.1）。

### 8.1 动机

`token_hash` 兼任「注册路径存储保护」与「RPC HMAC 密钥」：DB 泄露时注册路径仍安全，但 RPC 签名失守
（可伪造调度请求触发任意已注册任务）。对称方案无法弥补（Worker 必须能独立算出同一密钥）。
Ed25519 使 Admin 私钥签、Worker 存公钥——拖库（拿不到私钥）也无法伪造。

### 8.2 设计要点

- **密钥对**：Admin 生成 Ed25519 密钥对（JDK 15+ `EdEC` API，纯 JDK，无新依赖）；私钥存 Admin 侧
  （配置或新表，与凭证体系同一存储约束：私钥不明文落日志/变更表）；公钥经 `schedule-job.verify-public-key`
  配置分发到 Worker（公钥非机密）。
- **签名**：Admin 用私钥对规范化请求串签名，`ScheduleJobRequest` 携带 `signature`；Worker 用公钥验签。
- **派生参数退役**：salt / iterations / credentialVersion 不再需要（无 PBKDF2 派生）；timestamp / nonce(=requestId) / 去重集保留，防重放语义不变。
- **轮换**：私钥轮换 = 新密钥对 + Worker 滚动更新公钥配置（公钥可双值过渡，Worker 两把公钥任一通过即可，简化滚动）。
- **凭证版本解耦**：签名密钥与凭证版本无关，`credential_version` 状态机不受影响。

### 8.3 不实施原因（本轮拍板）

- 当前无「DB 被拖库」威胁模型（内网部署、凭证摘要已保护注册路径）。
- 实施与验证成本高（密钥管理、公钥分发、双算法过渡、E2E 改造）。
- 优先落地管理面（登录 + 轮换），使凭证体系可运营。

## 9. 实施影响面清单

- **新增**：`AdminAuthInterceptor`、`AdminAuthController`（login/logout）、`CredentialAdminController`、
  `CredentialChangePoller`、`CredentialExpiryMonitor`、`SessionRegistry`（会话表，可并入 AuthController）、
  管理接口 CAS SQL（Mapper 条件更新）。
- **修改**：`ScheduleProps`（adminUsername/adminPassword）、`WebMvcConfig`（注册拦截器）、
  `CredentialService`（prepare/activate/cancel/revoke 业务 + afterCommit 失效 + version 查询）、
  `CredentialRep` / `CredentialMapper` / `CredentialVersionMapper`（CAS 更新方法）、
  `OpenApiTokenInterceptor.normalize`（剥 Bearer 前缀抽公共静态方法复用）。
- **数据库**：无 schema 变更（三表已建；登录为配置化，无用户表）。
- **文档同步**：`AGENTS.md` §0.4（admin.username/password 键）、§5.5（管理接口与登录）、
  `CONTEXT.md`、本 spec 配套 plan。

## 10. 验收标准

1. 未配置 `schedule.admin.password`：启动 WARN，`/admin/**` 全部 401；配置后登录成功方可访问。
2. 登录成功返回 token；错误密码 / 无效 / 过期 token 均 401；`/admin/auth/login`、`/admin/auth/logout` 豁免拦截。
3. `prepare`：无身份 → 建 ACTIVE v1 并返回明文一次；有 active 无 pending → 建 PENDING 返回明文；
   已有 pending → `CREDENTIAL_PENDING_EXISTS`；明文只在 prepare 响应中出现一次。
4. 轮换全链路：prepare 后新旧 token 均可注册/心跳（active/pending 双版放行）；activate 后旧 token 立即 401、新 token 生效；
   **期间注册/心跳/派发零中断**。
5. `activate` 有在线实例仍持旧版本 → `CREDENTIAL_NOT_READY`；`force=true` + reason → 成功且 `forced_activation` 落库；
   `force=true` 无 reason → 400。
6. `cancel` 只取消 PENDING（ACTIVE 不受影响）；`revoke` 后该身份 `/open/**` 全部 401、派发停止，且不回退（Fail-Closed）。
7. 并发：同一身份并发 prepare 只有一个成功；并发 activate/cancel 同一 pending 只有一个生效（CAS 影响行数）。
8. 变更源：管理写路径与凭证写入同事务落 `credential_change`；**其他 Admin 节点约 1s 内旧 token 失效**；
   记录按 1h 清理且仅 leader 执行。
9. 过期版本按 `expire_time` 拒绝（`CREDENTIAL_EXPIRED`）；`job.credential.expiring` Gauge 上报 ≤30 天 ACTIVE 版本剩余天数。
10. 登录态贯穿：`prepared_by` / `activated_by` / `revoked_by` / `canceled_by` 为登录用户名（非 system），`UserSessionContext.getUserName()` 有真实值。
11. 全量单测 + IT + E2E 通过（E2E 扩展登录 + prepare → activate 轮换链路断言）。
