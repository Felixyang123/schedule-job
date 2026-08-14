package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Credential;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 凭证身份表（credential）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；另提供按 (applicationName, env) 幂等创建的
 * 条件插入（{@link #insertIfAbsent}），并发穿透由唯一键 {@code uk_app_env} 兜底。
 *
 * <p>管理接口（Spec 2026-08-14 §4）的状态流转以<b>指针 CAS</b> 控制并发：
 * 条件更新 + 影响行数检查（影响行数 0 = 并发冲突，上层回滚并返回对应错误码），不设独立数字锁。
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
            select #{credential.applicationName}, #{credential.env}, #{credential.activeVersion}, #{credential.pendingVersion},
                   #{credential.createTime}, #{credential.creator}, #{credential.updateTime}, #{credential.updater}
            from dual
            where not exists (
                select 1 from credential where application_name = #{credential.applicationName} and env = #{credential.env}
            )
            """)
    int insertIfAbsent(@Param("credential") Credential credential);

    /**
     * 按身份查询（唯一键命中）。
     */
    @Select("select * from credential where application_name = #{applicationName} and env = #{env}")
    Credential selectByIdentity(@Param("applicationName") String applicationName, @Param("env") String env);

    /**
     * 轮换 prepare：置 pending 指针，仅当当前无 pending 时生效（并发 prepare 只有一个成功）。
     *
     * @return 影响行数（0 = 已有 pending，并发冲突）
     */
    @Update("""
            update credential
            set pending_version = #{pendingVersion}, update_time = #{updateTime}, updater = #{updater}
            where id = #{id} and pending_version is null
            """)
    int updatePendingIfNull(@Param("id") Long id, @Param("pendingVersion") Integer pendingVersion,
                            @Param("updateTime") Date updateTime, @Param("updater") String updater);

    /**
     * 恢复 prepare（无 active 时直接建 ACTIVE）：置 active 指针，仅当当前无 active 时生效。
     *
     * @return 影响行数（0 = 已有 active，并发冲突）
     */
    @Update("""
            update credential
            set active_version = #{activeVersion}, update_time = #{updateTime}, updater = #{updater}
            where id = #{id} and active_version is null
            """)
    int updateActiveIfNull(@Param("id") Long id, @Param("activeVersion") Integer activeVersion,
                           @Param("updateTime") Date updateTime, @Param("updater") String updater);

    /**
     * activate 指针切换：active 指向新版本、清空 pending，仅当 pending 仍为期望版本时生效。
     *
     * @return 影响行数（0 = pending 指针已被并发修改，冲突）
     */
    @Update("""
            update credential
            set active_version = #{activeVersion}, pending_version = null,
                update_time = #{updateTime}, updater = #{updater}
            where id = #{id} and pending_version = #{expectedPending}
            """)
    int activatePointer(@Param("id") Long id, @Param("activeVersion") Integer activeVersion,
                        @Param("expectedPending") Integer expectedPending,
                        @Param("updateTime") Date updateTime, @Param("updater") String updater);

    /**
     * revoke 指针清理：active 置空（Fail-Closed），仅当 active 仍为期望版本时生效。
     *
     * @return 影响行数（0 = active 指针已被并发修改，冲突）
     */
    @Update("""
            update credential
            set active_version = null, update_time = #{updateTime}, updater = #{updater}
            where id = #{id} and active_version = #{expectedActive}
            """)
    int clearActivePointer(@Param("id") Long id, @Param("expectedActive") Integer expectedActive,
                           @Param("updateTime") Date updateTime, @Param("updater") String updater);

    /**
     * cancel 指针清理：pending 置空，仅当 pending 仍为期望版本时生效。
     *
     * @return 影响行数（0 = pending 指针已被并发修改，冲突）
     */
    @Update("""
            update credential
            set pending_version = null, update_time = #{updateTime}, updater = #{updater}
            where id = #{id} and pending_version = #{expectedPending}
            """)
    int clearPendingPointer(@Param("id") Long id, @Param("expectedPending") Integer expectedPending,
                            @Param("updateTime") Date updateTime, @Param("updater") String updater);
}
