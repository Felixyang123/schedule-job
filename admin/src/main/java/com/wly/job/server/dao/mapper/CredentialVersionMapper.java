package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.CredentialVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 凭证版本表（credential_version）MyBatis-Plus Mapper。
 *
 * <p>管理接口（Spec 2026-08-14 §4）的版本状态流转以<b>状态 CAS</b> 控制并发：
 * 条件更新（status = 期望前置状态）+ 影响行数检查，保证同一版本同一时刻只有一个流转生效。
 * 进入 REVOKED / CANCELED 的版本<b>清空 token_hash 与 salt</b>（只留脱敏值与审计，缩小密码材料留存面）。
 */
public interface CredentialVersionMapper extends BaseMapper<CredentialVersion> {

    /**
     * 按身份与版本号查询（唯一键 credential_id + version 命中）。
     */
    @Select("select * from credential_version where credential_id = #{credentialId} and version = #{version}")
    CredentialVersion selectByCredentialAndVersion(@Param("credentialId") Long credentialId,
                                                   @Param("version") Integer version);

    /**
     * activate：PENDING → ACTIVE + 激活审计，仅当当前为 PENDING 时生效。
     *
     * @return 影响行数（0 = 版本已被 cancel 或状态已变，冲突）
     */
    @Update("""
            update credential_version
            set status = #{statusActive}, activated_by = #{activatedBy}, activate_time = #{activateTime},
                forced_activation = #{forcedActivation}, activation_reason = #{activationReason}
            where credential_id = #{credentialId} and version = #{version} and status = #{statusPending}
            """)
    int activateVersion(@Param("credentialId") Long credentialId, @Param("version") Integer version,
                        @Param("statusPending") Integer statusPending, @Param("statusActive") Integer statusActive,
                        @Param("activatedBy") String activatedBy, @Param("activateTime") Date activateTime,
                        @Param("forcedActivation") Boolean forcedActivation,
                        @Param("activationReason") String activationReason);

    /**
     * revoke：ACTIVE → REVOKED + 吊销审计 + <b>清空 token_hash/salt</b>，仅当当前为 ACTIVE 时生效。
     *
     * @return 影响行数（0 = 无 active 版本或已被处理，冲突）
     */
    @Update("""
            update credential_version
            set status = #{statusRevoked}, revoked_by = #{revokedBy}, revoke_time = #{revokeTime},
                revoke_reason = #{revokeReason}, token_hash = null, salt = null
            where credential_id = #{credentialId} and version = #{version} and status = #{statusActive}
            """)
    int revokeActiveVersion(@Param("credentialId") Long credentialId, @Param("version") Integer version,
                            @Param("statusActive") Integer statusActive,
                            @Param("statusRevoked") Integer statusRevoked,
                            @Param("revokedBy") String revokedBy, @Param("revokeTime") Date revokeTime,
                            @Param("revokeReason") String revokeReason);

    /**
     * cancel：PENDING → CANCELED + 取消审计 + <b>清空 token_hash/salt</b>，仅当当前为 PENDING 时生效。
     *
     * @return 影响行数（0 = 无 pending 版本或已被处理，冲突）
     */
    @Update("""
            update credential_version
            set status = #{statusCanceled}, canceled_by = #{canceledBy}, cancel_time = #{cancelTime},
                cancel_reason = #{cancelReason}, token_hash = null, salt = null
            where credential_id = #{credentialId} and version = #{version} and status = #{statusPending}
            """)
    int cancelPendingVersion(@Param("credentialId") Long credentialId, @Param("version") Integer version,
                             @Param("statusPending") Integer statusPending,
                             @Param("statusCanceled") Integer statusCanceled,
                             @Param("canceledBy") String canceledBy, @Param("cancelTime") Date cancelTime,
                             @Param("cancelReason") String cancelReason);
}
