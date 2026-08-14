# 凭证管理实施 Plan（登录 / 管理接口 / 变更源轮询 / 过期预警）

> 决策来源：`docs/adr/0006-per-application-credentials.md`（accepted）、`docs/spec/2026-08-11-credential-spec.md`、
> `docs/spec/2026-08-14-credential-management-spec.md`（本轮的权威设计，见其中 §2 决策一览）。
> 前置：`2026-08-14-credential-implementation.md`（鉴权 + 签名 + 种子）已完成并合入 dev。
> **范围由用户拍板**：登录用配置化单用户；Ed25519 仅 spec 记录、不实施。

## 1. Goal

补齐凭证体系的管理面，使凭证可运营：

1. **登录与会话**：配置化单用户（`schedule.admin.username` / `schedule.admin.password`），进程内会话 token，
   `UserSessionContext` 获得真实写入方（此前恒为 null），全部 `/admin/**` 纳入登录保护。
2. **凭证管理接口**：`prepare` / `activate` / `cancel` / `revoke` 四个接口，两阶段轮换、状态机 CAS 并发控制、
   部署就绪校验（force 可跳过但须填原因）、Fail-Closed 紧急吊销。
3. **变更源广播**：`credential_change` 轮询线程（所有节点消费，不做 leader 门控）使跨节点缓存约 1s 收敛；
   记录清理（保留 1h，仅 leader）。
4. **过期预警**：`job.credential.expiring` Gauge（剩余天数），供 Prometheus 30/7/1 天告警规则消费。

## 2. 不做清单

- **Ed25519 非对称签名**：仅 `2026-08-14-credential-management-spec.md` §8 记录设计，不实施。
- **用户表 / RBAC**：登录为配置化单用户，无多账号、无角色（ADR 决策 #22「仅要求登录」）。
- **会话跨节点共享**：进程内会话表，多 Admin 节点登录互不可见（管理面单点访问，可接受）。
- **schema 变更**：三表已建，登录无用户表，本阶段数据库零变更。
- **凭证明文查询/回显**：明文只在 prepare 响应返回一次，无其他途径。

## 3. 关键设计决策（详见 Spec 2026-08-14）

### 3.1 登录与会话（Spec §3）

- `ScheduleProps` 新增 `adminUsername`（默认 `admin`）/ `adminPassword`（强制，未配置 → `/admin/**` 全 401 + 启动 WARN）。
- `POST /admin/auth/login`：常量时间比较密码 → `SecureRandom` 32B hex token → 内存会话表（TTL 30min 滑动续期）。
- `AdminAuthInterceptor`：拦 `/admin/**`（豁免 auth 两接口），Bearer token 校验 → `UserSessionContext.setUserSession(...)` → `afterCompletion` clear。
- Bearer 剥离逻辑：`OpenApiTokenInterceptor.normalize` 抽为公共静态方法复用。

### 3.2 管理接口（Spec §4）

- 基路径 `/admin/credential/*`，Body JSON + `Result<T>` 包装；操作人一律取 `UserSessionContext.getUserName()`。
- `prepare`：无身份/无 active → 建 ACTIVE（version=max+1）；有 active 无 pending → 建 PENDING 返回明文一次；有 pending → `CREDENTIAL_PENDING_EXISTS`。
- `activate`：默认部署就绪校验（在线实例均以 pending 版本心跳）→ 未就绪 `CREDENTIAL_NOT_READY`；`force=true` 须填 reason；
  CAS：pending→ACTIVE、旧 active→REVOKED（清 token_hash/salt）、指针切换。
- `cancel`：只取消 PENDING（reason 必填）；`revoke`：ACTIVE→REVOKED + active_version=NULL（Fail-Closed，reason 必填）。
- 全部写路径：同事务落 `credential_change`（change_type 1~4）+ `afterCommit` 后 `invalidate(app, env)`。

### 3.3 变更源轮询（Spec §5）

- `CredentialChangePoller`（SmartLifecycle）：每节点独立线程，1s 轮询 `id > watermark LIMIT 100` → `invalidate` 逐条；
  水印启动置 `max(id)`；**不做 leader 门控**（类注释显式声明原因）。
- 清理：每 300 次扫描 `DELETE ... create_time < now()-1h`，**leader 门控**。

### 3.4 过期预警（Spec §6）

- `CredentialExpiryMonitor`：60s 扫描 ACTIVE 版本，剩余 ≤30 天上报 `job.credential.expiring{application,env,version}=剩余天数`；
  leader 门控；判定用 `expire_time` 不用缓存 TTL。

## 4. 任务拆分

| # | 任务 | 内容 | 涉及文件 |
| :-- | :--- | :--- | :--- |
| 40 | admin: 配置与登录 | `ScheduleProps` 增 adminUsername/adminPassword；`AdminAuthController`（login/logout + 会话表）；`AdminAuthInterceptor`（Bearer 校验 + UserSessionContext 写入 + 滑动续期）；`WebMvcConfig` 注册 `/admin/**`；`OpenApiTokenInterceptor.normalize` 抽公共静态方法 | ScheduleProps、config/、controller/ |
| 41 | admin: Rep 与 CAS SQL | `CredentialMapper`/`CredentialVersionMapper` 条件更新（指针 CAS、状态 CAS）；`CredentialRep` 暴露 `updateIdentityConditional` / `updateVersionStatus` 等方法 | dao/mapper、dao/rep |
| 42 | admin: 管理接口服务 | `CredentialService` 增 prepare/activate/cancel/revoke（事务 + afterCommit invalidate + 错误码 + 就绪校验查询 instance）；`CredentialAdminController`（/admin/credential/*） | credential/、controller/ |
| 43 | admin: 变更源轮询 | `CredentialChangePoller`（消费 + 清理，生命周期与线程规范见 AGENTS.md §5.2） | schedule/ 或 credential/ |
| 44 | admin: 过期预警 | `CredentialExpiryMonitor`（扫描 + Gauge 上报，接入 `MetricsRegistry`） | credential/ 或 metrics/ |
| 45 | 测试 | 单测：登录/拦截器、prepare 状态机与明文一次、activate CAS/就绪/force、cancel/revoke、轮询消费与清理门控、指标上报；IT：轮换全链路（含跨节点收敛语义，单节点可验证 afterCommit 失效）；E2E 扩展登录 + prepare→activate 轮换断言 | admin/src/test、scripts/e2e-smoke-test.sh |
| 46 | 文档 | `AGENTS.md` §0.4（admin.username/password）、§5.5（管理接口与登录）；`CONTEXT.md`；本文档归档 | docs/ |

> 依赖顺序：40 → 41 → 42 → 43/44（并行）→ 45 → 46。
> 任务编号沿用 `2026-08-14-credential-implementation.md` 的 32~39 序列，本轮从 40 起。

## 5. 验收标准（对应 Spec 2026-08-14 §10）

1. 未配置 `schedule.admin.password`：启动 WARN，`/admin/**` 全部 401；配置后登录成功方可访问。
2. 登录成功返回 token；错误密码 / 无效 / 过期 token 均 401；auth 两接口豁免。
3. `prepare`：无身份建 ACTIVE v1 并返回明文一次；有 active 建 PENDING；有 pending → `CREDENTIAL_PENDING_EXISTS`；明文只在 prepare 出现一次。
4. 轮换全链路零调度中断：prepare 后新旧 token 均可注册/心跳；activate 后旧 token 立即 401；期间派发签名正常。
5. `activate` 未就绪 → `CREDENTIAL_NOT_READY`；`force=true`+reason 成功且 `forced_activation` 落库；force 无 reason → 400。
6. `cancel` 只取消 PENDING；`revoke` 后该身份 `/open/**` 全部 401、派发停止、不回退；恢复走 prepare。
7. 并发 prepare 只有一个成功；并发 activate/cancel 同一 pending 只有一个生效。
8. 写路径同事务落 `credential_change`；其他节点约 1s 内旧 token 失效；记录 1h 清理且仅 leader 执行。
9. 过期版本按 `expire_time` 拒绝；`job.credential.expiring` 上报 ≤30 天 ACTIVE 版本剩余天数。
10. `prepared_by` / `activated_by` / `revoked_by` / `canceled_by` 为登录用户名（非 system）。
11. 全量单测 + IT + E2E 通过（E2E 扩展登录 + 轮换链路断言）。

## 6. 风险与注意

- **activate 就绪校验的「在线」判定**：复用 `instance.status` + `expireTime` 现有语义，勿新造心跳窗口概念；
  就绪校验查询注意加索引（instance 表按 application_name+env 查询，schema 已含唯一键，必要时补充普通索引）。
- **CAS 失败回滚**：管理接口事务内任一 CAS 影响行数为 0 时抛 `ScheduleException` 回滚整个事务（版本行与指针不产生半状态）。
- **轮询线程优雅停机**：`CredentialChangePoller` 的 `stop()` 遵循 AGENTS.md §5.2（running 标记 + 优雅关闭 + shutdownNow 兜底）。
- **E2E 脚本**：新增登录步骤（curl 拿 token 再调管理接口），token 从响应 JSON 提取（脚本已有 jq/等价解析能力则复用）。
