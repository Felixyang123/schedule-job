# Job Local SSO Core Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 job Admin 复用 `D:\code\sso\sso\core` 的认证抽象，同时在 job 自己的 MySQL 中维护管理员用户、登录会话和首次改密状态。

**Architecture:** `admin` 引入已安装的 `com.wly.sso:core` 依赖，使用自定义 `AuthHelper`、`LoginStorage` 和 Header 版 `LoginHandler`。本地用户服务负责 BCrypt 校验及首次账号引导，本地会话存储负责持久化 `LoginInfo`；job 自己的 MVC 拦截器调用 Core `LoginHandler.checkToken` 并桥接到 `UserSessionContext`。Core 自带拦截器关闭，Redis 存储不启用。

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring MVC, MyBatis-Plus, MySQL 8, SSO Core JWT/`LoginHandler`, BCrypt。

**Spec:** `docs/superpowers/specs/2026-11-11-local-sso-login-design.md`

## Global Constraints

- 只修改当前隔离 worktree；不修改 `D:\code\sso` 源码仓库。
- SSO server 不作为 job 运行时依赖；不共享 SSO Redis、JWT 密钥、用户表或会话表。
- job 数据库只保存 BCrypt 密码摘要，不保存明文密码或可复用 Token 密钥材料。
- `login.props.jwt-secret` 必须显式配置且不少于 32 个 UTF-8 字节；`login.props.mode=none`，由 job 自己控制 `/admin/**` 认证范围。
- 初始化配置只在本地管理员账号不存在时创建账号，后续密码校验以数据库记录为准。
- 登录、改密、注销、会话失效、审计用户映射必须有单元测试；完成前运行相关模块测试和全量测试。

## Review Focus

- 初始管理员配置缺失或数据库已有账号时的启动行为：不得绕过认证或覆盖数据库密码；测试 `AdminUserInitializer` 的 fail-closed 和幂等性。
- 会话过期、注销后旧 JWT 仍可解析时的行为：数据库会话校验必须拒绝；测试 `DatabaseLoginStorage` 与拦截器。
- 同账号重复登录及改密后的旧 Token：新会话覆盖旧会话，改密清理会话；测试签名变化和删除行为。
- Header、Cookie、空 Token 和 malformed Authorization 输入：只接受配置的 Bearer Header 语义；测试认证拦截器返回 401 且清理上下文。
- 前端首次登录与强制改密：API 401 时回到登录态，`mustChangePassword` 时显示改密流程；测试静态页面中请求头和状态处理逻辑。

---

### Task 1: Install and declare the local SSO Core dependency

**Files:**
- Modify: `admin/pom.xml`
- Modify: `pom.xml` only if a shared version property is needed
- Modify: `admin/src/main/resources/application.yml`
- Modify: `admin/src/main/resources/application-dev.yml`
- Modify: `admin/src/main/resources/application-prod.yml`
- Test: `admin/src/test/java/com/wly/job/server/config/ProfileConfigTest.java`

**Interfaces:**
- Consumes: local artifact built from `D:\code\sso\sso\core`.
- Produces: `LoginHandler`, `LoginPropsConfig`, `LoginInfo` and `AuthHelper` types available to admin; `login.props.auth-model=job-local`, `mode=none`, `storage=local`, `token-header-key=Authorization`, `jwt-secret=${JOB_LOGIN_JWT_SECRET}`, `jwt-issuer=job-admin`.

- [ ] **Step 1: Build the unchanged SSO Core artifact**

Run from `D:\code\sso\sso`:

```bash
mvn -pl core -am install -DskipTests
```

Expected: `com.wly.sso:core:0.0.1-SNAPSHOT` is installed in the local Maven repository without modifying the SSO source tree.

- [ ] **Step 2: Add the admin dependency and configuration**

Add `com.wly.sso:core:0.0.1-SNAPSHOT` and the BCrypt crypto dependency required by the local user service. Configure the SSO Core properties in the common profile and remove the old login secret as the runtime authentication source while retaining the initial bootstrap fields under `schedule.admin`.

- [ ] **Step 3: Add configuration assertions**

Extend `ProfileConfigTest` to assert the SSO Core mode, header key, issuer and environment JWT secret placeholder. Keep production secrets as environment placeholders.

- [ ] **Step 4: Run the configuration tests**

Run: `mvn -pl admin -Dtest=ProfileConfigTest test`

Expected: PASS.

---

### Task 2: Add local user and persistent session schema

**Files:**
- Modify: `docs/sql/schema.sql`
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/AdminUser.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/entity/AdminSession.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/mapper/AdminUserMapper.java`
- Create: `admin/src/main/java/com/wly/job/server/dao/mapper/AdminSessionMapper.java`
- Test: `admin/src/test/java/com/wly/job/server/dao/entity/AdminAuthEntityTest.java`

**Interfaces:**
- Produces: `AdminUserMapper.findByUsername(String)`, `AdminUserMapper.findByAccountId(String)`, `AdminSessionMapper.findByAccountId(String)`, `AdminSessionMapper.deleteByAccountId(String)` and BaseMapper CRUD for both entities.
- `AdminUser` fields: id, accountId, username, passwordHash, enabled, mustChangePassword, passwordChangedAt, createTime, updateTime.
- `AdminSession` fields: accountId, accountName, signature, expireTime, rolesJson, permissionsJson, createTime, updateTime.

- [ ] **Step 1: Write entity and schema tests**

Assert table names, account/username uniqueness intent, the `must_change_password` default, and that no entity exposes a plaintext password field.

- [ ] **Step 2: Add `admin_user` and `admin_session` tables**

Use InnoDB/utf8mb4, unique keys for `account_id` and `username`, BCrypt hash length at least 255, and indexes on `expire_time`. `admin_session` must enforce one current session per account.

- [ ] **Step 3: Implement entities and mappers**

Use existing MyBatis-Plus entity/mapper conventions and annotated SQL for username/account lookups and account session deletion.

- [ ] **Step 4: Run DAO tests**

Run: `mvn -pl admin -Dtest=AdminAuthEntityTest test`

Expected: PASS.

---

### Task 3: Implement local authentication and Core adapters

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/auth/AdminUserService.java`
- Create: `admin/src/main/java/com/wly/job/server/auth/AdminUserInitializer.java`
- Create: `admin/src/main/java/com/wly/job/server/auth/DatabaseAuthHelper.java`
- Create: `admin/src/main/java/com/wly/job/server/auth/DatabaseLoginStorage.java`
- Create: `admin/src/main/java/com/wly/job/server/config/LocalSsoConfiguration.java`
- Test: `admin/src/test/java/com/wly/job/server/auth/AdminUserServiceTest.java`
- Test: `admin/src/test/java/com/wly/job/server/auth/DatabaseLoginStorageTest.java`
- Test: `admin/src/test/java/com/wly/job/server/auth/AdminUserInitializerTest.java`

**Interfaces:**
- `AdminUserService.authenticate(String username, String rawPassword): AdminUser` throws a machine-readable authentication failure for missing, disabled or mismatched accounts.
- `AdminUserService.changePassword(String accountId, String oldPassword, String newPassword): void` validates the old BCrypt hash, writes a new BCrypt hash, clears `mustChangePassword`, sets `passwordChangedAt`, and deletes all sessions for the account in one transaction.
- `DatabaseLoginStorage implements LoginStorage`: `save(LoginInfo)`, `get(String accountId)`, `update(LoginInfo)`, `remove(String accountId)`, `all()`; reject expired rows on read and serialize roles/permissions with the already available JSON library.
- `DatabaseAuthHelper implements AuthHelper`: loads the account by `LoginContext.accountId`, populates `LoginInfo` and local roles/permissions, and rejects disabled or missing accounts.
- `LocalSsoConfiguration` exposes beans named `defaultAuthHelper`, `defaultLoginStorage` and `loginHandler` using `HeaderLoginHandler(loginProps, defaultLoginHandler)`, so the Core auto-configuration supplies the standard login handler while job uses `Authorization`.

- [ ] **Step 1: Write failing service and storage tests**

Cover BCrypt success/failure, disabled account, minimum/new-password validation, first-login flag clearing, session persistence across a fresh service instance, expired session lookup, and delete-on-logout.

- [ ] **Step 2: Implement the user service and initializer**

Use `BCryptPasswordEncoder`. On startup, if the configured username is absent, require a nonblank bootstrap password, create a generated account ID, store the BCrypt hash and set `mustChangePassword=true`. If the username exists, do not overwrite its password or flags.

- [ ] **Step 3: Implement `DatabaseLoginStorage`**

Map Core `LoginInfo` to the session row, preserve signature and expiry, and return `null` for expired rows. `save` must replace the account’s previous session atomically enough to preserve one-session semantics.

- [ ] **Step 4: Implement Core bean wiring**

Keep `login.props.mode=none` to prevent SSO Core’s interceptor/filter from registering. Provide the custom named beans so Redis storage is never selected. Configure the header wrapper to read `Authorization` and normalize Bearer tokens through the Core utility.

- [ ] **Step 5: Run authentication unit tests**

Run: `mvn -pl admin -Dtest='*Auth*Test,*LoginStorageTest,*InitializerTest' test`

Expected: PASS.

---

### Task 4: Replace the in-memory admin session flow with SSO Core

**Files:**
- Create: `admin/src/main/java/com/wly/job/server/config/JobSsoAuthInterceptor.java`
- Modify: `admin/src/main/java/com/wly/job/server/config/WebMvcConfig.java`
- Modify: `admin/src/main/java/com/wly/job/server/controller/AuthController.java`
- Modify or remove: `admin/src/main/java/com/wly/job/server/config/AdminSessionRegistry.java`
- Modify: `admin/src/main/java/com/wly/job/server/config/BearerTokens.java` only if no longer needed
- Test: `admin/src/test/java/com/wly/job/server/config/JobSsoAuthInterceptorTest.java`
- Test: `admin/src/test/java/com/wly/job/server/controller/AuthControllerTest.java`

**Interfaces:**
- `JobSsoAuthInterceptor(LoginHandler, ObjectMapper) implements HandlerInterceptor`; `preHandle` checks Core token and sets `UserSessionContext` from `LoginInfo`, `afterCompletion` always clears it.
- `AuthController` uses `LoginHandler` and `AdminUserService`; `POST /admin/auth/login` authenticates locally, calls Core login, returns token/username/expireTime/mustChangePassword; `POST /admin/auth/logout` calls Core logout; `POST /admin/auth/password/change` changes the current account password.

- [ ] **Step 1: Write failing interceptor/controller tests**

Assert login success, wrong password 401, `mustChangePassword` response, valid token context injection, missing/invalid/expired token 401, context cleanup, logout invalidation, and password-change old-password validation.

- [ ] **Step 2: Implement the Core-backed controller**

Build `LoginContext` with local account ID/name before calling Core `LoginHandler.login`. Never accept applicant/account ID in the password-change request; derive the account from `UserSessionContext`/validated `LoginInfo`. Return existing `Result<T>` shape and machine-readable failure messages.

- [ ] **Step 3: Implement the job interceptor**

Register it for `/admin/**`, excluding login and logout. On `SsoException`, write HTTP 401 JSON without leaking token/parser details. Always clear `UserSessionContext` after completion.

- [ ] **Step 4: Remove the old in-memory session source**

Delete or fully detach `AdminSessionRegistry` from Spring wiring and update its tests so no runtime path uses an in-memory session map.

- [ ] **Step 5: Run controller and interceptor tests**

Run: `mvn -pl admin -Dtest='AuthControllerTest,JobSsoAuthInterceptorTest' test`

Expected: PASS; obsolete in-memory interceptor tests are removed or replaced.

---

### Task 5: Add first-login password-change UI and authenticated API calls

**Files:**
- Modify: `admin/src/main/resources/static/admin.html`
- Test: `admin/src/test/java/com/wly/job/server/controller/AdminHtmlContractTest.java` or an existing static-resource test location

**Interfaces:**
- Browser storage key: `job.admin.token`.
- All `/admin/**` fetches use same-origin `/admin` base and `Authorization: Bearer <token>`.
- Login response fields: `token`, `username`, `expireTime`, `mustChangePassword`.
- Password-change request fields: `oldPassword`, `newPassword`.

- [ ] **Step 1: Add the contract test**

Assert the static page contains the login form, logout control, token header construction, 401 handling and password-change endpoint/fields.

- [ ] **Step 2: Add login and logout state**

Show the login panel before protected data loads, persist the returned token only for the browser session, and clear it on logout or 401. Use a relative API base instead of hardcoded `localhost:8100`.

- [ ] **Step 3: Add forced and voluntary password change**

When `mustChangePassword` is true, show the change form before normal management actions. After success, clear the old token and require login again because the server invalidates the session.

- [ ] **Step 4: Run static contract tests**

Run: `mvn -pl admin -Dtest=AdminHtmlContractTest test`

Expected: PASS.

---

### Task 6: Update schema/configuration documentation and verify the full module

**Files:**
- Modify: `docs/sql/schema.sql`
- Modify: `admin/src/main/resources/application.yml`
- Modify: `admin/src/main/resources/application-dev.yml`
- Modify: `admin/src/main/resources/application-prod.yml`
- Modify: `AGENTS.md` sections 0.4 and 5.5
- Modify: `docs/superpowers/specs/2026-11-11-local-sso-login-design.md` only for implementation notes if needed

- [ ] **Step 1: Verify all configuration profiles**

Document `JOB_LOGIN_JWT_SECRET`, the bootstrap-only `schedule.admin.username/password`, `login.props.*`, the local tables and the first-login change flow. Remove claims that the admin password remains the runtime authentication source.

- [ ] **Step 2: Run focused admin tests**

Run: `mvn -pl admin test`

Expected: PASS with zero failures.

- [ ] **Step 3: Run the full reactor tests**

Run: `mvn test`

Expected: PASS across common, core, admin, starter and samples.

- [ ] **Step 4: Inspect the final diff and worktree boundary**

Run: `git status --short`, `git diff --check`, and `git diff --stat` from `D:/code/job/.agents/worktrees/job-login-service`.

Expected: only the isolated worktree contains the planned changes, no whitespace errors, and no SSO repository files are modified.
