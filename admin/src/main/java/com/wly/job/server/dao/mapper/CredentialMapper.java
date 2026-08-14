package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Credential;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 凭证身份表（credential）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；另提供按 (applicationName, env) 幂等创建的
 * 条件插入（{@link #insertIfAbsent}），并发穿透由唯一键 {@code uk_app_env} 兜底。
 */
public interface CredentialMapper extends BaseMapper<Credential> {

    /**
     * 条件插入凭证身份：仅当 (application_name, env) 不存在时插入（Worker 注册去重的同款模式，
     * 见 JobMapper.insertIfAbsent）。
     *
     * @return 影响行数（0 表示已存在）
     */
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("""
            insert into credential(`application_name`, `env`, `active_version`, `pending_version`,
                                   `create_time`, `creator`, `update_time`, `updater`)
            select #{applicationName}, #{env}, #{activeVersion}, #{pendingVersion},
                   #{createTime}, #{creator}, #{updateTime}, #{updater}
            from dual
            where not exists (
                select 1 from credential where application_name = #{applicationName} and env = #{env}
            )
            """)
    int insertIfAbsent(Credential credential);

    /**
     * 按身份查询（唯一键命中）。
     */
    @Select("select * from credential where application_name = #{applicationName} and env = #{env}")
    Credential selectByIdentity(@Param("applicationName") String applicationName, @Param("env") String env);
}
