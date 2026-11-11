# Job 本地 SSO Core 登录设计

## 意图

为 job Admin 提供基于本地 MySQL 用户和会话的登录认证，同时复用 `D:\code\sso\sso\core` 提供的登录处理器、JWT Token 和认证抽象。job 独立运行，不依赖 SSO server，也不与 SSO 服务共享 Redis、用户库或会话库。

## 目标

- `admin` 模块直接依赖并使用 `com.wly.sso:core`。
- 用户信息保存在 job 数据库，至少包含账号标识、用户名、BCrypt 密码摘要、启用状态和审计时间。
- 登录会话保存在 job 数据库；同一账号保持单会话语义，注销和过期能够使会话失效。
- 保留 `/admin/auth/login`、`/admin/auth/logout` 接口契约，前端使用 Bearer Token。
- 所有 `/admin/**` 管理接口通过 SSO Core 的 `LoginHandler.checkToken` 校验，并将 `LoginInfo` 映射到现有 `UserSessionContext`，继续支持操作人审计。
- 未登录、Token 无效、Token 过期和会话失效统一返回 HTTP 401。

## 非目标

- 不部署或调用 `D:\code\sso\sso\server`。
- 不共享 SSO 的 Redis、JWT 密钥、用户表或会话表。
- 不在本轮实现多租户、RBAC 管理页面、用户增删改接口或跨服务单点登录。
- 不保留进程内 `AdminSessionRegistry` 作为实际会话来源。

## 约束

- 继续使用 job 已有 MySQL、MyBatis-Plus 和 Spring MVC。
- 密码使用 BCrypt 摘要，明文不落库；运行时不再以明文配置作为登录校验事实源。
- `sso:core` 的 Token 需要显式配置至少 32 字节的 `login.props.jwt-secret`；认证模式设为 job-local，Core 自带 MVC 拦截器关闭，由 job 适配拦截器负责 `/admin/**` 路径和 `UserSessionContext` 注入。
- 会话存储实现 `LoginStorage`，使用 job 自己的 `admin_session` 表；用户认证实现 `AuthHelper`/本地认证服务，使用 job 自己的 `admin_user` 表。
- 初始管理员通过现有 `schedule.admin.username/password` 仅在数据库不存在该账号时完成一次 BCrypt 引导；后续登录校验数据库记录，配置变更不会覆盖已有用户。

## 方案比较

### 方案 A：接入 `sso:core`，本地实现用户和会话存储（采用）

job 引入 `sso:core`，实现 `LoginStorage` 和本地账号认证适配器，使用 Core 的 `LoginHandler`、JWT Token 和 `LoginInfo`。优点是复用认证框架的 Token 生命周期和扩展点，数据完全由 job 控制；代价是新增两张表及 MyBatis 持久化适配层。

### 方案 B：复制 SSO 的 JWT 和登录逻辑到 job

job 自行复制 Token、登录和会话处理代码。实现直观，但会形成两套认证实现，后续修复和行为容易分叉，不符合复用本地框架的目标。

### 方案 C：调用独立 SSO server

job 将登录和会话校验委托给 SSO server。该方式需要网络依赖或共享密钥/Redis，且用户明确要求 job 维护独立的本地用户和登录状态，因此不采用。

## 设计

### 认证链路

1. `POST /admin/auth/login` 接收用户名和密码。
2. 本地用户服务按用户名查询 `admin_user`，检查启用状态并用 BCrypt 校验密码。
3. 构造 `LoginContext`，调用 SSO Core `LoginHandler.login`；Core 生成 JWT，并通过自定义 `LoginStorage` 将 `LoginInfo` 保存到 `admin_session`。
4. 返回现有登录响应中的 Token、用户名和到期时间。
5. `JobSsoAuthInterceptor` 对 `/admin/**`（排除 login/logout）调用 `LoginHandler.checkToken`；成功后将 `LoginInfo.accountId/accountName` 写入 `UserSessionContext`，请求完成后清理。
6. `POST /admin/auth/logout` 调用 `LoginHandler.logout`，按 Token 对应账号删除本地会话。

### 数据模型

- `admin_user`：`id`、`account_id`、`username`、`password_hash`、`enabled`、`create_time`、`update_time`；`account_id` 和 `username` 唯一。
- `admin_session`：`account_id`、`account_name`、`signature`、`expire_time`、`roles_json`、`permissions_json`、`create_time`、`update_time`；以 `account_id` 为主键或唯一键，登录覆盖旧会话。

### 配置

```yaml
login:
  props:
    auth-model: job-local
    mode: none
    storage: local
    token-header-key: Authorization
    jwt-secret: ${JOB_LOGIN_JWT_SECRET}
    jwt-issuer: job-admin

schedule:
  admin:
    username: admin       # 仅用于首次初始化
    password: ${JOB_ADMIN_INITIAL_PASSWORD} # 仅用于首次初始化
```

`JOB_LOGIN_JWT_SECRET` 必须显式配置且不少于 32 个 UTF-8 字节。`admin.password` 在初始化后不参与登录校验；若数据库没有初始账号且未提供密码，启动失败并保持 Fail-Closed。

## 密码生命周期

- 首次从配置引导的管理员记录 `must_change_password=1`，登录响应包含 `mustChangePassword=true`。
- 增加受保护的 `POST /admin/auth/password/change` 接口，请求只包含 `oldPassword` 和 `newPassword`，账号从当前会话取得。
- 改密前校验旧密码，使用 BCrypt 保存新摘要；新密码满足最小长度、非空且不能与旧密码相同。
- 改密成功后清理该账号的本地会话，使当前及其他旧 Token 立即失效，并将 `must_change_password` 置为 0，记录 `password_changed_at`。
- 后续主动改密复用同一接口；初始化配置只在账号不存在时使用，不覆盖数据库中的密码。

## 验收

- 启动后能够按配置自动创建初始管理员，数据库中只保存 BCrypt 摘要。
- 正确密码返回可用 Token；错误密码、禁用账号和缺失配置均拒绝。
- 携带 Token 的管理请求可通过校验，并能在现有服务中取得真实操作人；无效、过期、注销后的 Token 均返回 401。
- 会话数据来自 job MySQL，重启 job 后仍可按未过期状态验证；不需要 Redis 或 SSO server。
- 现有凭证管理、作业管理、开放接口鉴权和调度行为测试通过。
- `docs/sql/schema.sql`、开发/生产配置和 README/AGENTS 相关配置说明同步更新。
