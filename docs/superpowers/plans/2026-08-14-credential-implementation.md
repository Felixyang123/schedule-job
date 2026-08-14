# 凭证体系实施 Plan（2026-08-14，本阶段范围收敛版）

> 决策来源：`docs/adr/0006-per-application-credentials.md`（accepted）、`docs/spec/2026-08-11-credential-spec.md`。
> **本阶段范围由用户拍板收敛**：实施 `/open/**` 按身份鉴权 + RPC HMAC 签名 + Worker 验签/去重 + 种子凭证初始化；
> **凭证管理接口（prepare/activate/cancel/revoke）、登录机制、credential_change 轮询线程不在本阶段**（下轮补）。

## 1. Goal

把「全局共享 `schedule.access-token`」替换为「按 (applicationName, env) 发放凭证」的鉴权模型：

1. **/open/**（作业注册、实例心跳）：** 请求以 `X-Job-Group` + `X-Job-Env` 声明身份，`Authorization: Bearer {明文}` 携带凭证，Admin 按身份查 active/pending 两版摘要校验，Controller 二次校验 Header 与 Body 身份一致。
2. **Admin → Worker RPC 派发：** 不再传可复用凭证；以 `token_hash`（PBKDF2 摘要）为 HMAC-SHA256 密钥对规范化请求签名，Worker 用本地明文派生同一密钥验签，±30s 时间窗 + requestId 去重防重放。
3. **Worker 侧身份：** 新增 `schedule-job.application-name`（强制配置，为空启动失败）；`env` 从 activeProfiles 提取（首个非 test 值，无则 `default`）；删除 `schedule-job.group.name`（组模式 discoveryKey 改用 application-name）。
4. **凭证种子：** Admin 配置 `schedule.credential.seed`（`app:env:明文` 逗号分隔），启动时幂等创建 ACTIVE v1；未配置时 `/open/**` 一律 401（Fail-Closed）。

## 2. 不做清单（下轮）

- 凭证管理接口（prepare / activate / cancel / revoke）与登录/会话机制 → 下轮（`UserSessionContext` 暂保持无写入方）。
- `credential_change` 轮询线程与记录清理 → 下轮；本轮表结构建好，缓存靠 TTL + 强制重载自愈（seed 只发生在启动期，所有节点同源，缓存不会陈旧）。
- 凭证过期预警指标 `job.credential.expiring` → 下轮（无管理接口无写路径）。

## 3. 关键设计决策

### 3.1 凭证表结构（schema.sql 新增，实体照建）

- `credential`：`application_name` + `env` 唯一键 + `active_version` / `pending_version` 指针。
- `credential_version`：`credential_id` + `version` 唯一键；`status`(PENDING/ACTIVE/REVOKED/CANCELED)、`token_hash`、`salt`、`iterations`、`masked_token`、`expire_time`、审计列（prepared_by/create_time、activated_by/activate_time/forced_activation/activation_reason、revoked_by/revoke_time/revoke_reason、canceled_by/cancel_time/cancel_reason）。进入 REVOKED/CANCELED 清空 token_hash/salt。
- `credential_change`：`application_name` / `env` / `change_type` / `operator` / `create_time`（本轮只建表，无消费者）。
- `instance` 表新增：`application_name`、`env`、`credential_version` 三列。

### 3.2 凭证种子初始化（CredentialSeedInitializer）

- 配置键：`schedule.credential.seed`，格式逗号分隔 `app:env:明文token`（明文含 `:` 时需 base64 或限定格式，本轮按简单 `app:env:token` 解析）。
- 幂等逻辑：对每个 (app, env)，查 credential 身份：无身份 → 创建身份 + ACTIVE v1（PBKDF2 派生摘要落库，明文不落库）；已有身份且无 active 版本 → 创建 ACTIVE v1；已有 active → 跳过（seed 变更不覆盖，防误改线上凭证）。
- 执行时机：ApplicationRunner（启动最后阶段），失败不阻断启动（WARN），保证 Fail-Closed 语义（seed 未生效则 401）。
- PBKDF2：`PBKDF2WithHmacSHA256` + 16 字节随机盐（SecureRandom）+ 迭代次数 120_000，派生 256 bit。

### 3.3 /open/** 鉴权（OpenApiTokenInterceptor 重写）

- Header：`X-Job-Group: {applicationName}`、`X-Job-Env: {env}`；`Authorization: Bearer {明文}`。
- 流程：取 Header 身份 → 查 credential（无身份 → 401 `CREDENTIAL_UNKNOWN`）→ 取 active/pending 两版 → 对每版用各自 salt/iterations 派生比较（常量时间 `MessageDigest.isEqual`）→ 命中即放行，把 `(applicationName, env, credentialVersion)` 写入 request attribute（`OpenApiAuthContext`）。
- 校验失败强制重载：缓存 miss / 不匹配 → 绕过缓存 reload 一次再判定（防「新凭证生效」方向误拒）。
- 错误码可区分（响应 message）：缺 Header → `MISSING_CREDENTIAL_IDENTITY`；token 错误 → `CREDENTIAL_INVALID`；版本过期 → `CREDENTIAL_EXPIRED`（按 expire_time 判定）；身份与 Body 不一致 → `CREDENTIAL_IDENTITY_MISMATCH`（Controller 层）。

### 3.4 Controller 二次校验（OpenJobController）

- `register(JobInfo)`：校验 `jobInfo` 中身份与 Header 一致。JobInfo **不新增身份字段**——Body 身份取哪里？方案：作业注册请求体 JobInfo 不含身份字段，身份只从 Header 来；`registerJob` 按 Header 身份写库关联（`job` 表不加列，仅日志/变更记录 operator 记 `app:env`）。实例注册 `registerInstance(JobInstance)`：`JobInstance` 新增 `applicationName`/`env` 字段（Worker 填充），校验与 Header 一致。
- 校验不通过 → 401。

### 3.5 RPC 派发签名（ScheduleServiceTemplate + CredentialService）

- `JobInstance` 新增 `applicationName` / `env`（Admin 从 instance 表读出时填充；Worker 心跳时也填充）。
- 派发时按 `instance.applicationName + instance.env` 查 active 版本 → 取 `token_hash` 作 HMAC 密钥。
- 规范化串：`requestId + "|" + jobname + "|" + executeParam + "|" + timestamp + "|" + requestId(nonce)`（字段以 `|` 连接，null 以空串）。
- 请求新增字段：`credentialVersion` / `salt` / `iterations` / `timestamp` / `signature`；删除 `token`。
- 凭证缺失/已过期 → 抛 ScheduleException（上层按失败重试路径处理），不派发。

### 3.6 Worker 验签（JobInstanceHandler 重写鉴权段）

- 拒绝条件：`|now - timestamp| > 30s`（`CREDENTIAL_TIMESTAMP_EXPIRED`）；`requestId` 已见过（`CREDENTIAL_REPLAY`）；`credentialVersion` 与本地不符（`CREDENTIAL_VERSION_MISMATCH`）；签名不匹配（`CREDENTIAL_SIGNATURE_INVALID`）。
- 验签流程：本地明文 token（`schedule-job.accessToken` 保留语义为明文凭证）→ 按请求 salt/iterations 派生 derivedKey（**业务线程池内派生**，I/O 线程只做时间窗 + 去重 + 版本预检）→ HMAC-SHA256 与 signature 常量时间比较。
- derivedKey 缓存：`Map<(version, salt, iterations), byte[]>`，上限 4，淘汰最小 version；命中时单次 HMAC。
- 去重集：`ConcurrentHashMap<String, Long>`（requestId → 到达时间），TTL 60s 懒清理；容量上限 10 万，超限先清过期，仍超则拒绝新请求。**去重在 I/O 线程完成**，重放请求不进业务线程池。
- 验签失败响应：`success=false, error=unauthorized`，写回后关闭连接（保留现状）。
- `JobInstanceHandler` 构造参数：`(InnerJobRegistry, String plainToken, String expectedApplicationName)`？——Worker 侧是否需要校验 applicationName？请求不带身份字段（身份隐含在凭证里），Worker 用本地明文派生，只验签即可；本地明文就是身份锚点。构造参数改为 `(InnerJobRegistry, RequestAuthenticator)`，由 factory 注入 `HmacRequestAuthenticator`（内含明文 + 派生缓存 + 去重集）。

### 3.7 Worker 侧身份注入链（starter/core）

- `ScheduleJobConfigProps`：删除 `group.name`（Group 只留 `enabled`）；`accessToken` 保留（语义 = 本应用明文凭证，必填，为空启动失败）；新增 `application-name`（必填）。
- `ScheduleJobAutoConfiguration`：注入 `Environment`，提取 env = 首个非 test activeProfile，无则 `default`；装配 factory 时传入 applicationName/env。
- `ScheduleJobCoreFactory`：构造参数 `(port, serverAddresses, accessToken, applicationName, env, httpConnectTimeout, httpReadTimeout, serverSelector, enableGroup, heartbeatInterval)`；组模式下 discoveryKey 用 applicationName（原用 group.name）。
- `ScheduleJobAnnotationProcessor`：构造 `JobInstance` 时填充 `applicationName` / `env`（从 factory 取）。
- `RestClientHelper`：新增 `defaultHeader("X-Job-Group", appName)` / `defaultHeader("X-Job-Env", env)`（builder 支持）。

### 3.8 Admin 凭证缓存（CredentialCache）

- 键 `(applicationName, env)` → active 版本信息（version/salt/iterations/tokenHash/expireTime）+ pending 版本信息。
- 60s TTL 自愈；提供 `invalidate(app, env)`（供下轮管理接口 afterCommit 调用，本轮 seed 初始化后调用）；查询失败不缓存异常（下次直查）。
- 校验失败强制重载在拦截器层实现（绕过缓存直查一次）。

### 3.9 instance 表写入

- `Instance` 实体 + `InstanceMapper.saveOrUpdate` 新增三列（`application_name`/`env`/`credential_version`）。
- `JobInstancePersistStorage.put`：从 `JobInstance` 新字段填充（Worker 心跳填充 app/env；credentialVersion 由 Admin 按鉴权结果写入——在 OpenJobController/Service 层把鉴权得到的版本 set 到 JobInstance 再入库）。
- `JobBeanConverter` 双向转换同步三列。
- `instance` 表唯一键 `uk_name_host_port` 不变（同发现键 + 同 host/port 仍唯一）。

### 3.10 samples / 脚本 / 文档

- `job-sample`、`register-center-registry-sample`：`application.yml` 改 `application-name: ${spring.application.name}`、删除 `group.name`、`accessToken` 保留；Admin `application-dev.yml` 删除 `access-token`、新增 `schedule.credential.seed: job-sample-server:dev:defaultToken,register-center-sample-server:dev:defaultToken`（按实际 profile 对 env 值）。
- `scripts/e2e-smoke-test.sh`：admin 启动参数与断言适配（token 注入方式不变，seed 走配置）。
- `docs/sql/schema.sql`：3 张表 + instance 3 列。
- `AGENTS.md` §0.4/§4/§5.5、`CONTEXT.md`（新增 Credential / Application Identity 术语）、本 plan。

## 4. 任务拆分

| # | 任务 | 内容 |
| :-- | :--- | :--- |
| 32 | common | PBKDF2/HMAC 工具 + `ScheduleJobRequest` 改造 + `JobInstance` 增字段 |
| 33 | core | `JobInstanceHandler` 验签/去重 + `RestClientHelper` 身份头 |
| 34 | starter | `ScheduleJobConfigProps`/`AutoConfiguration`/`CoreFactory`/`AnnotationProcessor` 身份链 |
| 35 | admin | 3 表实体/Rep + `CredentialService`/`CredentialCache` + `CredentialSeedInitializer` + instance 表扩展 |
| 36 | admin | `OpenApiTokenInterceptor` 重写 + `OpenJobController` 二次校验 + ScheduleProps 删 access-token |
| 37 | admin | `ScheduleServiceTemplate` 派发签名 |
| 38 | 文档/脚本 | schema.sql + samples 配置 + e2e 脚本 + AGENTS.md/CONTEXT.md |
| 39 | 验证 | 全量构建 + 单测修复/新增 + IT + E2E |

## 5. 验收标准（本阶段可验证子集）

1. 未配置 `schedule-job.application-name` 或 `accessToken` 的 Worker 启动失败并给出明确错误。
2. `/open/**` 缺身份 Header / token 错误 / 身份与 Body 不一致，均 401 且错误码可区分。
3. 正确身份 + 正确 token 可正常注册作业与实例；实例行落库含 application_name/env/credential_version。
4. `schedule.credential.seed` 未配置时 `/open/**` 一律 401；配置后幂等创建 ACTIVE v1（重复启动不重复建）。
5. RPC 派发：请求含 credentialVersion/salt/iterations/timestamp/signature，不含 token。
6. 篡改请求任一签名字段（jobname/executeParam/timestamp）后 Worker 拒绝执行（签名不匹配）。
7. 同一 requestId 重放第二次被拒（去重集），且业务方法未被反射调用。
8. 超 ±30s 时间窗的请求被拒。
9. Worker 同版本二次请求不再派生（derivedKey 缓存命中），派生不在 Netty I/O 线程。
10. 旧配置 `schedule.access-token` / `schedule-job.group.name` 已从代码与配置中删除。
11. 全量单测 + IT + E2E 通过（E2E 中注册/心跳/派发全链路在种子凭证下正常）。
