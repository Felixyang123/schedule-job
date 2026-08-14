package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialChange;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.mapper.CredentialChangeMapper;
import com.wly.job.server.dao.mapper.CredentialMapper;
import com.wly.job.server.dao.mapper.CredentialVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 凭证体系仓储层（Repository，ADR-0006 / Spec 2026-08-14 §4）。
 *
 * <p>封装 {@code credential} / {@code credential_version} / {@code credential_change} 三表的查询与写入：
 * 查询供 /open/** 鉴权与派发签名；管理写路径（prepare/activate/cancel/revoke）以
 * <b>CAS 条件更新 + 影响行数检查</b>控制并发（不设独立数字锁），变更记录与凭证写入同事务。
 */
@Repository
@RequiredArgsConstructor
public class CredentialRep {

    private final CredentialMapper credentialMapper;

    private final CredentialVersionMapper versionMapper;

    private final CredentialChangeMapper changeMapper;

    /** 按身份查询凭证身份行（无则返回 null） */
    public Credential findByIdentity(String applicationName, String env) {
        return credentialMapper.selectByIdentity(applicationName, env);
    }

    /** 按主键查询凭证身份行（无则返回 null，供过期预警反查 app/env） */
    public Credential findIdentityById(Long id) {
        return credentialMapper.selectById(id);
    }

    /** 查询全部 ACTIVE 版本行（供过期预警扫描剩余有效期） */
    public List<CredentialVersion> listActiveVersions() {
        return versionMapper.selectList(Wrappers.<CredentialVersion>lambdaQuery()
                .eq(CredentialVersion::getStatus, CredentialVersion.STATUS_ACTIVE));
    }

    /** 按身份幂等创建凭证身份行（已存在返回 0，不抛异常） */
    public int insertIdentityIfAbsent(Credential credential) {
        return credentialMapper.insertIfAbsent(credential);
    }

    /** 按凭证 ID 与版本号查询版本行 */
    public CredentialVersion findVersion(Long credentialId, Integer version) {
        return versionMapper.selectByCredentialAndVersion(credentialId, version);
    }

    /** 按凭证 ID 查询全部版本行（版本号升序） */
    public List<CredentialVersion> listVersions(Long credentialId) {
        return versionMapper.selectList(Wrappers.<CredentialVersion>lambdaQuery()
                .eq(CredentialVersion::getCredentialId, credentialId)
                .orderByAsc(CredentialVersion::getVersion));
    }

    /** 插入版本行（返回主键） */
    public void insertVersion(CredentialVersion version) {
        versionMapper.insert(version);
    }

    /** 更新版本行 */
    public void updateVersion(CredentialVersion version) {
        versionMapper.updateById(version);
    }

    /** 更新身份行（指针与审计） */
    public void updateIdentity(Credential credential) {
        credentialMapper.updateById(credential);
    }

    // ---------------- 管理接口 CAS（Spec 2026-08-14 §4） ----------------

    /** 轮换 prepare：置 pending 指针（并发冲突返回 0，由 Service 层映射错误码） */
    public int updatePendingIfNull(Long id, Integer pendingVersion, Date updateTime, String updater) {
        return credentialMapper.updatePendingIfNull(id, pendingVersion, updateTime, updater);
    }

    /** 恢复 prepare：置 active 指针（并发冲突返回 0） */
    public int updateActiveIfNull(Long id, Integer activeVersion, Date updateTime, String updater) {
        return credentialMapper.updateActiveIfNull(id, activeVersion, updateTime, updater);
    }

    /** activate：指针切换（active→新版本、清 pending），并发冲突返回 0 */
    public int activatePointer(Long id, Integer activeVersion, Integer expectedPending,
                               Date updateTime, String updater) {
        return credentialMapper.activatePointer(id, activeVersion, expectedPending, updateTime, updater);
    }

    /** revoke：active 指针置空（Fail-Closed），并发冲突返回 0 */
    public int clearActivePointer(Long id, Integer expectedActive, Date updateTime, String updater) {
        return credentialMapper.clearActivePointer(id, expectedActive, updateTime, updater);
    }

    /** cancel：pending 指针置空，并发冲突返回 0 */
    public int clearPendingPointer(Long id, Integer expectedPending, Date updateTime, String updater) {
        return credentialMapper.clearPendingPointer(id, expectedPending, updateTime, updater);
    }

    /** activate：版本 PENDING → ACTIVE + 审计（并发冲突返回 0） */
    public int activateVersion(Long credentialId, Integer version, String activatedBy, Date activateTime,
                               boolean forcedActivation, String activationReason) {
        return versionMapper.activateVersion(credentialId, version,
                CredentialVersion.STATUS_PENDING, CredentialVersion.STATUS_ACTIVE,
                activatedBy, activateTime, forcedActivation, activationReason);
    }

    /** revoke：版本 ACTIVE → REVOKED + 审计 + 清空 token_hash/salt（并发冲突返回 0） */
    public int revokeActiveVersion(Long credentialId, Integer version, String revokedBy,
                                   Date revokeTime, String revokeReason) {
        return versionMapper.revokeActiveVersion(credentialId, version,
                CredentialVersion.STATUS_ACTIVE, CredentialVersion.STATUS_REVOKED,
                revokedBy, revokeTime, revokeReason);
    }

    /** cancel：版本 PENDING → CANCELED + 审计 + 清空 token_hash/salt（并发冲突返回 0） */
    public int cancelPendingVersion(Long credentialId, Integer version, String canceledBy,
                                    Date cancelTime, String cancelReason) {
        return versionMapper.cancelPendingVersion(credentialId, version,
                CredentialVersion.STATUS_PENDING, CredentialVersion.STATUS_CANCELED,
                canceledBy, cancelTime, cancelReason);
    }

    /** 追加凭证变更源记录（仅缓存失效信号，不携带密码材料；与凭证写入同事务） */
    public void recordChange(String applicationName, String env, int changeType, String operator) {
        CredentialChange change = CredentialChange.builder()
                .applicationName(applicationName)
                .env(env)
                .changeType(changeType)
                .operator(operator)
                .createTime(new Date())
                .build();
        changeMapper.insert(change);
    }

    /** 查询变更源（id 水印后，升序限量）——CredentialChangePoller 消费 */
    public List<CredentialChange> listChangesAfter(Long watermark, int limit) {
        return changeMapper.selectList(Wrappers.<CredentialChange>lambdaQuery()
                .gt(CredentialChange::getId, watermark)
                .orderByAsc(CredentialChange::getId)
                .last("LIMIT " + limit));
    }

    /** 查询变更源最大 id（进程内水印初始化，启动不回放历史） */
    public Long maxChangeId() {
        return changeMapper.selectList(Wrappers.<CredentialChange>lambdaQuery()
                        .select(CredentialChange::getId)
                        .orderByDesc(CredentialChange::getId)
                        .last("LIMIT 1"))
                .stream()
                .map(CredentialChange::getId)
                .findFirst()
                .orElse(0L);
    }

    /** 清理超过保留时长的变更源记录（纯 housekeeping，仅 leader 执行） */
    public int deleteChangesBefore(Date cutoff) {
        return changeMapper.delete(Wrappers.<CredentialChange>lambdaQuery()
                .lt(CredentialChange::getCreateTime, cutoff));
    }
}
