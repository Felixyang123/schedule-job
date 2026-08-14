package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 凭证版本实体，对应 {@code credential_version} 表（ADR-0006）。
 *
 * <p>每版一行：版本号、状态、PBKDF2 摘要（{@code token_hash} + {@code salt} +
 * {@code iterations}）、脱敏值、有效期，以及全套审计列（版本 + 历史 + 审计兼任）。
 *
 * <p>状态单向流转，版本号不复用：
 * <pre>
 * PENDING ──activate──> ACTIVE ──下一版 activate / revoke──> REVOKED
 *    │
 *    └────cancel──────> CANCELED
 * </pre>
 * <b>进入 REVOKED / CANCELED 后清除 {@code tokenHash} 与 {@code salt}</b>，
 * 只留脱敏值与审计信息，缩小密码材料留存面。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "credential_version")
public class CredentialVersion {

    /** 状态：待激活 */
    public static final int STATUS_PENDING = 0;

    /** 状态：生效中 */
    public static final int STATUS_ACTIVE = 1;

    /** 状态：已吊销 */
    public static final int STATUS_REVOKED = 2;

    /** 状态：已取消 */
    public static final int STATUS_CANCELED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属凭证身份 ID（credential 表主键） */
    private Long credentialId;

    /** 版本号（身份内自增，状态机指针引用） */
    private Integer version;

    /** 版本状态：0 PENDING / 1 ACTIVE / 2 REVOKED / 3 CANCELED */
    private Integer status;

    /** PBKDF2 摘要（Base64，派生密钥；吊销/取消后清空） */
    private String tokenHash;

    /** 派生盐（Base64，吊销/取消后清空） */
    private String salt;

    /** PBKDF2 迭代次数 */
    private Integer iterations;

    /** 脱敏展示值（如 sj_****a8f2，永久保留） */
    private String maskedToken;

    /** 有效期截止时间（过期判定依据本列，不得用缓存 TTL 代替） */
    private Date expireTime;

    /** 创建（prepare）审计 */
    private String preparedBy;

    private Date createTime;

    /** 激活审计 */
    private String activatedBy;

    private Date activateTime;

    private Boolean forcedActivation;

    private String activationReason;

    /** 吊销审计 */
    private String revokedBy;

    private Date revokeTime;

    private String revokeReason;

    /** 取消审计 */
    private String canceledBy;

    private Date cancelTime;

    private String cancelReason;
}
