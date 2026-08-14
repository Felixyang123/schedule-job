package com.wly.job.server.credential;

import com.wly.job.common.security.Pbkdf2Digest;
import com.wly.job.server.config.ScheduleProps;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.rep.CredentialRep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 凭证查询与种子初始化服务（ADR-0006）。
 *
 * <p><b>查询</b>：按 (applicationName, env) 返回 active/pending 两版摘要，供
 * {@code /open/**} 拦截器校验与派发签名。进程内缓存（60s TTL + 校验失败强制重载），
 * 高频心跳不直打 DB；本阶段无管理接口写路径，缓存仅在种子初始化后主动失效。
 *
 * <p><b>种子初始化</b>（{@link #initFromSeed}，ApplicationReady 时执行）：
 * 从 {@code schedule.credential.seed}（逗号分隔 {@code app:env:明文}）幂等创建凭证：
 * 无身份 → 建身份 + ACTIVE v1；有身份无 active 版本 → 建 ACTIVE v1（吊销后恢复路径）；
 * 已有 active → 跳过（seed 变更不覆盖线上凭证）。未配置 seed 时保持 Fail-Closed
 * （/open/** 一律 401，旧版「未配置即拒绝」语义延续）。
 *
 * <p>凭证管理接口（prepare/activate/cancel/revoke）不在本阶段（用户拍板收敛），
 * 表结构与状态机指针已就位，下轮接入。
 */
@Slf4j
@Service
public class CredentialService {

    /** 摘要缓存 TTL：60s 自愈（无写路径时仅防进程内重复查询打 DB） */
    static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(60);

    private final CredentialRep credentialRep;

    private final ScheduleProps props;

    /** 进程内摘要缓存：(app:env) -> 缓存项 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CredentialService(CredentialRep credentialRep, ScheduleProps props) {
        this.credentialRep = credentialRep;
        this.props = props;
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
            log.warn("schedule.credential.seed not configured; /open/** will reject all requests (Fail-Closed)");
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
            Credential newIdentity = new Credential();
            newIdentity.setApplicationName(applicationName);
            newIdentity.setEnv(env);
            newIdentity.setCreateTime(new Date());
            newIdentity.setCreator("system");
            newIdentity.setUpdateTime(new Date());
            newIdentity.setUpdater("system");
            if (credentialRep.insertIdentityIfAbsent(newIdentity) == 0) {
                // 并发穿透：另一节点已创建，重查
                identity = credentialRep.findByIdentity(applicationName, env);
            } else {
                identity = newIdentity;
            }
            if (identity == null) {
                log.error("credential seed identity creation failed: {}:{}", applicationName, env);
                return false;
            }
        }

        // 建 ACTIVE 新版本：首次创建与吊销后恢复同一路径（ADR 决策 #14）；
        // 版本号不复用（状态机唯一键 credential_id+version），恢复时取 max+1。
        var derivation = Pbkdf2Digest.deriveNew(plaintext);
        int version = credentialRep.listVersions(identity.getId()).stream()
                .mapToInt(CredentialVersion::getVersion)
                .max()
                .orElse(0) + 1;
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
        credentialRep.insertVersion(credentialVersion);

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

    /** 主动失效（种子初始化 / 下轮管理接口 afterCommit 后调用） */
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
