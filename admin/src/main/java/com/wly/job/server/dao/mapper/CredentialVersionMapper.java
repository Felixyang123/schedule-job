package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.CredentialVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 凭证版本表（credential_version）MyBatis-Plus Mapper。
 */
public interface CredentialVersionMapper extends BaseMapper<CredentialVersion> {

    /**
     * 按身份与版本号查询（唯一键 credential_id + version 命中）。
     */
    @Select("select * from credential_version where credential_id = #{credentialId} and version = #{version}")
    CredentialVersion selectByCredentialAndVersion(@Param("credentialId") Long credentialId,
                                                   @Param("version") Integer version);
}
