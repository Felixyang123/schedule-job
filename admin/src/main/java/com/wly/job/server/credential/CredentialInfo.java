package com.wly.job.server.credential;

import com.wly.job.server.dao.entity.CredentialVersion;

import java.util.Date;

/**
 * 凭证身份查询结果（进程内只读模型）：身份 + active/pending 两版摘要信息。
 *
 * <p>供两处消费：
 * <ul>
 *   <li><b>/open/** 拦截器</b>：用两版各自的 salt/iterations 对出示明文派生后常量时间比较，
 *       命中任一版即放行（轮换过渡期新旧凭证均可注册）；</li>
 *   <li><b>派发签名</b>：取 active 版本 tokenHash 作 HMAC 密钥。</li>
 * </ul>
 */
public record CredentialInfo(
        String applicationName,
        String env,
        VersionInfo active,
        VersionInfo pending) {

    /**
     * 单版本摘要信息（tokenHash 作存储摘要 / HMAC 密钥双用，ADR-0006 已知限制）。
     */
    public record VersionInfo(int version, String tokenHash, String salt, int iterations,
                              Date expireTime) {

        /**
         * 从实体构造（本阶段不做管理接口，仅有 ACTIVE 状态参与鉴权与签名；
         * pending 仅用于 prepare 后的过渡期——下轮管理接口接入后生效）。
         */
        public static VersionInfo of(CredentialVersion entity) {
            return new VersionInfo(entity.getVersion(), entity.getTokenHash(), entity.getSalt(),
                    entity.getIterations(), entity.getExpireTime());
        }
    }
}
