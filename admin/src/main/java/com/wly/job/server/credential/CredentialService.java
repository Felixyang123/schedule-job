package com.wly.job.server.credential;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialChange;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.dao.rep.InstanceRep;
import com.wly.job.server.pojo.resp.ActivateCredentialResp;
import com.wly.job.server.pojo.resp.CancelCredentialResp;
import com.wly.job.server.pojo.resp.PrepareCredentialResp;
import com.wly.job.server.pojo.resp.RevokeCredentialResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 凭证查询、种子初始化与管理接口服务（ADR-0006 / Spec 2026-08-14）。
 *
 * <p><b>查询</b>：按 (applicationName, env) 返回 active/pending 两版摘要，供
 * {@code /open/**} 拦截器校验与派发签名。进程内缓存（60s TTL + 校验失败强制重载 +
 * 变更源轮询跨节点失效），高频心跳不直打 DB。
 *
 * <p><b>种子初始化</b>（{@link #initFromSeed}，ApplicationReady 时执行）：
 * 从 {@code schedule.credential-seed}（逗号分隔 {@code app:env:明文}）幂等创建凭证：
 * 无身份 → 建身份 + ACTIVE v1；有身份无 active 版本 → 建 ACTIVE v1（吊销后恢复路径）；
 * 已有 active → 跳过（seed 变更不覆盖线上凭证）。未配置 seed 时保持 Fail-Closed
 * （/open/** 一律 401）。
 *
 * <p><b>管理接口</b>（{@link #prepare}/{@link #activate}/{@link #cancel}/{@link #revoke}）：
 * 状态机 CAS 并发控制（条件更新 + 影响行数检查，不设独立数字锁）；写路径与
 * {@code credential_change} 同事务，提交后（afterCommit）主动失效本节点缓存，
 * 其余节点由变更源轮询（约 1s）收敛。操作人强制取 {@link UserSessionContext#getUserName()}。
 */
@Slf4j
@Service
public class CredentialService {

    /** 摘要缓存 TTL：60s 自愈（变更源轮询约 1s 已覆盖写路径失效，TTL 仅兜底） */
    static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(60);

    /** 有效期默认天数（ADR 决策 #9） */
    static final int DEFAULT_EXPIRE_DAYS = 90;

    /** 有效期上限天数（ADR 决策 #9） */
    static final int MAX_EXPIRE_DAYS = 365;

    // ---------------- 管理接口错误码（Spec 2026-08-14 §4.6） ----------------

    public static final String ERR_UNKNOWN = "CREDENTIAL_UNKNOWN";
    public static final String ERR_PENDING_EXISTS = "CREDENTIAL_PENDING_EXISTS";
    public static final String ERR_NO_PENDING = "CREDENTIAL_NO_PENDING";
    public static final String ERR_NO_ACTIVE = "CREDENTIAL_NO_ACTIVE";
    public static final String ERR_NOT_READY = "CREDENTIAL_NOT_READY";
    public static final String ERR_VERSION_MISMATCH = "CREDENTIAL_VERSION_MISMATCH";
    public static final String ERR_INVALID_EXPIRY = "CREDENTIAL_INVALID_EXPIRY";
    public static final String ERR_REASON_REQUIRED = "CREDENTIAL_REASON_REQUIRED";
    public static final String ERR_AUTH_REQUIRED = "CREDENTIAL_AUTH_REQUIRED";

    private final CredentialRep credentialRep;

    private final ScheduleProps props;

    /** 实例仓储：activate 部署就绪校验（在线实例均已用 pending 版本心跳） */
    private final InstanceRep instanceRep;

    /** 进程内摘要缓存：(app:env) -> 缓存项 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialService(CredentialRep credentialRep, ScheduleProps props, InstanceRep instanceRep) {
        this.credentialRep = credentialRep;
        this.props = props;
        this.instanceRep = instanceRep;
    }

    /**
     * 按身份查凭证（active/pending 两版）。缓存未命中/过期时直查 DB。
     *
     * @return 空表示身份不存在（/open/** 应 401 CREDENTIAL_UNKNOWN）
     */
    public Optional<CredentialInfo> lookup(String applicationName, String env) {
        if (!StringUtils.hasText(applicationName) || !StringUtils.hasText(env)) {
            return Optional.empty();
        }
        String key = cacheKey(applicationName, env);
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expireAt() > now) {
            return Optional.ofNullable(entry.info());
        }
        CredentialInfo loaded = load(applicationName, env);
        cache.put(key, new CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, loaded));
        return Optional.ofNullable(loaded);
    }

    /**
     * 绕过缓存直查一次（拦截器校验失败时强制重载，防「新凭证生效」方向误拒，Spec §4）。
     */
    public Optional<CredentialInfo> lookupForced(String applicationName, String env) {
        String key = cacheKey(applicationName, env);
        cache.remove(key);
        return lookup(applicationName, env);
    }

    /**
     * 种子初始化：ApplicationReady 时执行，幂等。失败不阻断启动（WARN），
     * 保持 Fail-Closed（seed 未生效则 /open/** 401）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initFromSeed() {
        String seed = props.getCredentialSeed();
        if (!StringUtils.hasText(seed)) {
            log.warn("schedule.credential-seed not configured; /open/** will reject all requests (Fail-Closed)");
            return;
        }
        int created = 0;
        for (String item : seed.split(",")) {
            try {
                if (createFromSeedItem(item.trim())) {
                    created++;
                }
            } catch (Exception e) {
                log.error("credential seed init failed for item: {}, keep going", item, e);
            }
        }
        log.info("credential seed init done, created={}, seedCount={}", created, seed.split(",").length);
    }

    // ---------------- 管理接口（Spec 2026-08-14 §4） ----------------

    /**
     * 创建 / 轮换准备（ADR 决策 #14：create/reissue 合并进 prepare）。
     * 无身份 → 建身份 + 直接 ACTIVE；有 active 无 pending → 建 PENDING（返回明文一次）；
     * 已有 pending → CREDENTIAL_PENDING_EXISTS（过期 pending 亦须人工 cancel，ADR 决策 #18）。
     */
    @Transactional
    public PrepareCredentialResp prepare(String applicationName, String env, Integer expireDays) {
        if (!StringUtils.hasText(applicationName) || !StringUtils.hasText(env)) {
            throw new ScheduleException(ERR_UNKNOWN, "applicationName and env are required");
        }
        int days = normalizeExpireDays(expireDays);
        String operator = requireOperator();
        Credential identity = credentialRep.findByIdentity(applicationName, env);
        if (identity == null) {
            identity = createIdentity(applicationName, env, operator);
        }
        if (identity.getPendingVersion() != null) {
            throw new ScheduleException(ERR_PENDING_EXISTS,
                    "credential pending version exists, cancel it first");
        }
        // 无 active（首次创建 / 吊销后恢复）→ 直接建 ACTIVE；否则建 PENDING 进入轮换过渡
        boolean directActive = identity.getActiveVersion() == null;
        String plaintext = generatePlaintext();
        var derivation = Pbkdf2Digest.deriveNew(plaintext);
        Date now = new Date();
        int version = nextVersion(identity.getId());
        CredentialVersion credentialVersion = new CredentialVersion();
        credentialVersion.setCredentialId(identity.getId());
        credentialVersion.setVersion(version);
        credentialVersion.setStatus(directActive ? CredentialVersion.STATUS_ACTIVE : CredentialVersion.STATUS_PENDING);
        credentialVersion.setTokenHash(derivation.digest());
        credentialVersion.setSalt(derivation.salt());
        credentialVersion.setIterations(derivation.iterations());
        credentialVersion.setMaskedToken(mask(plaintext));
        credentialVersion.setExpireTime(new Date(now.getTime() + days * 86_400_000L));
        credentialVersion.setPreparedBy(operator);
        credentialVersion.setCreateTime(now);
        if (directActive) {
            credentialVersion.setActivatedBy(operator);
            credentialVersion.setActivateTime(now);
            credentialVersion.setForcedActivation(false);
            credentialVersion.setActivationReason("direct create / recovery");
        }
        try {
            credentialRep.insertVersion(credentialVersion);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发 prepare 分配了相同版本号：唯一键 (credential_id, version) 冲突。
            // 正常路径继续走指针 CAS（必然失败并抛正确错误码），本事务随回滚撤销。
            log.debug("concurrent prepare same version {} detected for {}:{}, CAS will decide",
                    version, applicationName, env);
        }

        // 指针 CAS：并发 prepare 只有一个成功（恢复路径冲突返回 VERSION_MISMATCH）
        int cas = directActive
                ? credentialRep.updateActiveIfNull(identity.getId(), version, now, operator)
                : credentialRep.updatePendingIfNull(identity.getId(), version, now, operator);
        if (cas == 0) {
            throw new ScheduleException(directActive ? ERR_VERSION_MISMATCH : ERR_PENDING_EXISTS,
                    "concurrent prepare detected, credential pointer changed");
        }
        credentialRep.recordChange(applicationName, env, CredentialChange.CHANGE_PREPARE, operator);
        invalidateAfterCommit(applicationName, env);
        log.info("credential prepared, identity: {}:{}, version: {}, status: {}",
                applicationName, env, version, directActive ? "ACTIVE" : "PENDING");
        return new PrepareCredentialResp(applicationName, env, version,
                credentialVersion.getMaskedToken(), plaintext, credentialVersion.getExpireTime());
    }

    /**
     * 轮换生效：默认校验部署就绪度（该身份所有在线实例均已用 pending 版本心跳），
     * 未就绪 → CREDENTIAL_NOT_READY；{@code force=true} 跳过校验但必须填 reason。
     * 生效后旧 ACTIVE 版本立即吊销（无宽限期，ADR 决策 #12）。
     */
    @Transactional
    public ActivateCredentialResp activate(String applicationName, String env, boolean force, String reason) {
        if (!StringUtils.hasText(applicationName) || !StringUtils.hasText(env)) {
            throw new ScheduleException(ERR_UNKNOWN, "applicationName and env are required");
        }
        if (force && !StringUtils.hasText(reason)) {
            throw new ScheduleException(ERR_REASON_REQUIRED, "reason is required when force=true");
        }
        String operator = requireOperator();
        Credential identity = requireIdentity(applicationName, env);
        Integer pending = identity.getPendingVersion();
        if (pending == null) {
            throw new ScheduleException(ERR_NO_PENDING, "no pending credential version to activate");
        }
        if (!force) {
            checkDeployReady(identity, pending);
        }
        Date now = new Date();
        // 版本 CAS：PENDING → ACTIVE（已被 cancel 则 0 行 → 冲突）
        if (credentialRep.activateVersion(identity.getId(), pending, operator, now,
                force, force ? reason : "routine activation") == 0) {
            throw new ScheduleException(ERR_VERSION_MISMATCH, "pending version no longer PENDING");
        }
        // 旧 ACTIVE 立即吊销（清 token_hash/salt；无旧版或已被并发处理时忽略）
        Integer oldActive = identity.getActiveVersion();
        if (oldActive != null && !Objects.equals(oldActive, pending)) {
            credentialRep.revokeActiveVersion(identity.getId(), oldActive, operator, now, "rotated by activate");
        }
        // 指针 CAS：active → 新版本、清 pending
        if (credentialRep.activatePointer(identity.getId(), pending, pending, now, operator) == 0) {
            throw new ScheduleException(ERR_VERSION_MISMATCH, "pending pointer changed concurrently");
        }
        credentialRep.recordChange(applicationName, env, CredentialChange.CHANGE_ACTIVATE, operator);
        invalidateAfterCommit(applicationName, env);
        log.info("credential activated, identity: {}:{}, version: {}, forced: {}",
                applicationName, env, pending, force);
        return new ActivateCredentialResp(applicationName, env, pending, oldActive);
    }

    /**
     * 取消待激活：只影响 PENDING，不影响 ACTIVE（ADR 决策 #17）；reason 必填。
     */
    @Transactional
    public CancelCredentialResp cancel(String applicationName, String env, String reason) {
        if (!StringUtils.hasText(applicationName) || !StringUtils.hasText(env)) {
            throw new ScheduleException(ERR_UNKNOWN, "applicationName and env are required");
        }
        if (!StringUtils.hasText(reason)) {
            throw new ScheduleException(ERR_REASON_REQUIRED, "reason is required");
        }
        String operator = requireOperator();
        Credential identity = requireIdentity(applicationName, env);
        Integer pending = identity.getPendingVersion();
        if (pending == null) {
            throw new ScheduleException(ERR_NO_PENDING, "no pending credential version to cancel");
        }
        Date now = new Date();
        // 版本 CAS：PENDING → CANCELED + 清 token_hash/salt
        if (credentialRep.cancelPendingVersion(identity.getId(), pending, operator, now, reason) == 0) {
            throw new ScheduleException(ERR_NO_PENDING, "pending version already processed");
        }
        if (credentialRep.clearPendingPointer(identity.getId(), pending, now, operator) == 0) {
            throw new ScheduleException(ERR_VERSION_MISMATCH, "pending pointer changed concurrently");
        }
        credentialRep.recordChange(applicationName, env, CredentialChange.CHANGE_CANCEL, operator);
        invalidateAfterCommit(applicationName, env);
        log.info("credential canceled, identity: {}:{}, version: {}", applicationName, env, pending);
        return new CancelCredentialResp(applicationName, env, pending);
    }

    /**
     * 紧急吊销：ACTIVE 立即失效并清 active 指针（Fail-Closed——无可用版本时 /open/** 一律 401、
     * 派发停止，不回退全局令牌，ADR 决策 #19）；恢复走 prepare。reason 必填。
     */
    @Transactional
    public RevokeCredentialResp revoke(String applicationName, String env, String reason) {
        if (!StringUtils.hasText(applicationName) || !StringUtils.hasText(env)) {
            throw new ScheduleException(ERR_UNKNOWN, "applicationName and env are required");
        }
        if (!StringUtils.hasText(reason)) {
            throw new ScheduleException(ERR_REASON_REQUIRED, "reason is required");
        }
        String operator = requireOperator();
        Credential identity = requireIdentity(applicationName, env);
        Integer active = identity.getActiveVersion();
        if (active == null) {
            throw new ScheduleException(ERR_NO_ACTIVE, "no active credential version to revoke");
        }
        Date now = new Date();
        // 版本 CAS：ACTIVE → REVOKED + 清 token_hash/salt
        if (credentialRep.revokeActiveVersion(identity.getId(), active, operator, now, reason) == 0) {
            throw new ScheduleException(ERR_NO_ACTIVE, "active version already revoked");
        }
        if (credentialRep.clearActivePointer(identity.getId(), active, now, operator) == 0) {
            throw new ScheduleException(ERR_VERSION_MISMATCH, "active pointer changed concurrently");
        }
        credentialRep.recordChange(applicationName, env, CredentialChange.CHANGE_REVOKE, operator);
        invalidateAfterCommit(applicationName, env);
        log.warn("credential revoked, identity: {}:{}, version: {}, reason: {}",
                applicationName, env, active, reason);
        return new RevokeCredentialResp(applicationName, env, active);
    }

    // ---------------- 管理接口私有辅助 ----------------

    /** activate 部署就绪校验：所有在线实例均已用 pending 版本心跳；无在线实例视为就绪。 */
    private void checkDeployReady(Credential identity, Integer pendingVersion) {
        List<Instance> onlineInstances = instanceRep.list(Wrappers.<Instance>lambdaQuery()
                .eq(Instance::getApplicationName, identity.getApplicationName())
                .eq(Instance::getEnv, identity.getEnv())
                .eq(Instance::getStatus, Instance.ONLINE));
        for (Instance instance : onlineInstances) {
            if (!Objects.equals(instance.getCredentialVersion(), pendingVersion)) {
                throw new ScheduleException(ERR_NOT_READY,
                        "instance still using old credential version (host=" + instance.getHost()
                                + ":" + instance.getPort() + ", version=" + instance.getCredentialVersion()
                                + "), deploy first or force=true");
            }
        }
    }

    private Credential requireIdentity(String applicationName, String env) {
        Credential identity = credentialRep.findByIdentity(applicationName, env);
        if (identity == null) {
            throw new ScheduleException(ERR_UNKNOWN, "credential identity not found: " + applicationName + ":" + env);
        }
        return identity;
    }

    private int normalizeExpireDays(Integer expireDays) {
        int days = expireDays == null ? DEFAULT_EXPIRE_DAYS : expireDays;
        if (days <= 0 || days > MAX_EXPIRE_DAYS) {
            throw new ScheduleException(ERR_INVALID_EXPIRY,
                    "expireDays must be in (0, 365], got: " + days);
        }
        return days;
    }

    private String requireOperator() {
        String operator = UserSessionContext.getUserName();
        if (!StringUtils.hasText(operator)) {
            throw new ScheduleException(ERR_AUTH_REQUIRED, "login required to manage credentials");
        }
        return operator;
    }

    private Credential createIdentity(String applicationName, String env, String operator) {
        Credential identity = new Credential();
        identity.setApplicationName(applicationName);
        identity.setEnv(env);
        identity.setCreateTime(new Date());
        identity.setCreator(operator);
        identity.setUpdateTime(new Date());
        identity.setUpdater(operator);
        if (credentialRep.insertIdentityIfAbsent(identity) == 0) {
            // 并发穿透：另一节点已创建，重查
            Credential existing = credentialRep.findByIdentity(applicationName, env);
            if (existing == null) {
                throw new ScheduleException(ERR_UNKNOWN, "credential identity creation failed");
            }
            return existing;
        }
        return identity;
    }

    private int nextVersion(Long credentialId) {
        return credentialRep.listVersions(credentialId).stream()
                .mapToInt(CredentialVersion::getVersion)
                .max()
                .orElse(0) + 1;
    }

    /** 生成随机明文凭证：{@code sj_} 前缀 + 24 字节 hex（48 字符）。 */
    String generatePlaintext() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("sj_");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 缓存失效挂 afterCommit（Spec §4 硬性约束 1：禁止在事务内 evict，防并发读回填旧值）；非事务上下文直接失效 */
    private void invalidateAfterCommit(String applicationName, String env) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(applicationName, env);
                }
            });
        } else {
            invalidate(applicationName, env);
        }
    }

    // ---------------- 种子初始化（沿用既有逻辑） ----------------

    /**
     * 解析并创建单个种子项 {@code app:env:明文}。
     *
     * @return true 表示本次创建了新 ACTIVE 版本
     */
    private boolean createFromSeedItem(String item) {
        String[] parts = item.split(":");
        if (parts.length != 3 || !StringUtils.hasText(parts[0])
                || !StringUtils.hasText(parts[1]) || !StringUtils.hasText(parts[2])) {
            log.warn("invalid credential seed item (expect app:env:plaintext): {}", item);
            return false;
        }
        String applicationName = parts[0].trim();
        String env = parts[1].trim();
        String plaintext = parts[2].trim();

        Credential identity = credentialRep.findByIdentity(applicationName, env);
        if (identity != null && identity.getActiveVersion() != null) {
            // 已有 active 版本：seed 变更不覆盖线上凭证（防误改）
            log.debug("credential seed skip, identity already active: {}:{}", applicationName, env);
            return false;
        }
        if (identity == null) {
            identity = createIdentity(applicationName, env, "system");
        }

        // 建 ACTIVE 新版本：首次创建与吊销后恢复同一路径（ADR 决策 #14）；
        // 版本号不复用（状态机唯一键 credential_id+version），恢复时取 max+1。
        var derivation = Pbkdf2Digest.deriveNew(plaintext);
        int version = nextVersion(identity.getId());
        CredentialVersion credentialVersion = new CredentialVersion();
        credentialVersion.setCredentialId(identity.getId());
        credentialVersion.setVersion(version);
        credentialVersion.setStatus(CredentialVersion.STATUS_ACTIVE);
        credentialVersion.setTokenHash(derivation.digest());
        credentialVersion.setSalt(derivation.salt());
        credentialVersion.setIterations(derivation.iterations());
        credentialVersion.setMaskedToken(mask(plaintext));
        credentialVersion.setExpireTime(new Date(System.currentTimeMillis()
                + TimeUnit.DAYS.toMillis(90)));
        credentialVersion.setPreparedBy("system");
        credentialVersion.setCreateTime(new Date());
        credentialVersion.setActivatedBy("system");
        credentialVersion.setActivateTime(new Date());
        credentialVersion.setForcedActivation(false);
        credentialVersion.setActivationReason("seed init");
        try {
            credentialRep.insertVersion(credentialVersion);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发节点 seed 分配相同版本号：唯一键冲突视为并发跳过（幂等，另一节点已建）
            log.debug("concurrent seed same version {} for {}:{}, skip", version, applicationName, env);
            return false;
        }

        identity.setActiveVersion(version);
        identity.setPendingVersion(null);
        identity.setUpdateTime(new Date());
        identity.setUpdater("system");
        credentialRep.updateIdentity(identity);

        invalidate(applicationName, env);
        log.info("credential seed created ACTIVE v{}, identity: {}:{}", version, applicationName, env);
        return true;
    }

    /**
     * 从 DB 加载身份两版摘要（无身份返回 null）。
     */
    private CredentialInfo load(String applicationName, String env) {
        Credential identity = credentialRep.findByIdentity(applicationName, env);
        if (identity == null) {
            return null;
        }
        CredentialInfo.VersionInfo active = null;
        CredentialInfo.VersionInfo pending = null;
        if (identity.getActiveVersion() != null) {
            CredentialVersion v = credentialRep.findVersion(identity.getId(), identity.getActiveVersion());
            if (v != null) {
                active = CredentialInfo.VersionInfo.of(v);
            }
        }
        if (identity.getPendingVersion() != null) {
            CredentialVersion v = credentialRep.findVersion(identity.getId(), identity.getPendingVersion());
            if (v != null) {
                pending = CredentialInfo.VersionInfo.of(v);
            }
        }
        return new CredentialInfo(applicationName, env, active, pending);
    }

    /** 主动失效（种子初始化 / 管理接口 afterCommit / 变更源轮询后调用） */
    public void invalidate(String applicationName, String env) {
        cache.remove(cacheKey(applicationName, env));
    }

    private static String cacheKey(String applicationName, String env) {
        return applicationName + ":" + env;
    }

    /** 脱敏：前 3 字符 + **** + 后 4 字符（明文过短时整体掩码） */
    static String mask(String plaintext) {
        if (plaintext == null || plaintext.length() <= 7) {
            return "****";
        }
        return plaintext.substring(0, 3) + "****" + plaintext.substring(plaintext.length() - 4);
    }

    private record CacheEntry(long expireAt, CredentialInfo info) {
    }
}
