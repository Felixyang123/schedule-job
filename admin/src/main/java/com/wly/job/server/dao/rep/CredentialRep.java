package com.wly.job.server.dao.rep;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.Credential;
import com.wly.job.server.dao.entity.CredentialVersion;
import com.wly.job.server.dao.mapper.CredentialMapper;
import com.wly.job.server.dao.mapper.CredentialVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 凭证体系仓储层（Repository，ADR-0006）。
 *
 * <p>封装 {@code credential} 与 {@code credential_version} 两表的查询与写入。
 * 本阶段（无管理接口）以查询为主：按身份读取 active/pending 两版摘要供鉴权与派发签名；
 * 种子初始化走条件插入 + 版本插入。
 */
@Repository
@RequiredArgsConstructor
public class CredentialRep {

    private final CredentialMapper credentialMapper;

    private final CredentialVersionMapper versionMapper;

    /** 按身份查询凭证身份行（无则返回 null） */
    public Credential findByIdentity(String applicationName, String env) {
        return credentialMapper.selectByIdentity(applicationName, env);
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
}
