package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Job;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 作业表（job）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；删除走 MyBatis-Plus 逻辑删除配置，生成 deleted=1 的
 * Update 语句而非物理删除。
 */
public interface JobMapper extends BaseMapper<Job> {

    /**
     * 条件插入：作业名（group_name + name）不存在时才插入，用于 Worker 重复注册的幂等去重。
     *
     * <p>相较捕获 {@code DuplicateKeyException}，正常的重复注册走返回 0 的普通路径，不再以异常做
     * 流程控制；也不像 {@code INSERT IGNORE} 那样把数据截断、非法值等错误一并降级为 warning
     * 而被误判为「作业已存在」（见 Spec 2026-08-12 §5）。
     *
     * <p><b>NOT EXISTS 子查询故意不带 {@code deleted} 条件</b>：逻辑删除的作业仍占用唯一键，
     * Worker 重启重新注册时不得复活已删除任务（ADR-0005 §4 删除语义）。
     *
     * <p>唯一键 {@code uk_group_name_name} 仍是并发兜底：两个 Worker 同时穿透 NOT EXISTS 时由唯一键
     * 裁决，落败方抛 {@code DuplicateKeyException}，调用方需捕获并视为已存在。
     *
     * @return 1 表示首次插入（{@code job.id} 被回填），0 表示作业已存在
     */
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("""
            INSERT INTO job (group_name, name, description, execute_param, cron,
                             status, type, strategy, finished, deleted,
                             create_time, update_time, creator, updater)
            SELECT #{groupName}, #{name}, #{description}, #{executeParam}, #{cron},
                   #{status}, #{type}, #{strategy}, #{finished}, #{deleted},
                   #{createTime}, #{updateTime}, #{creator}, #{updater}
            FROM DUAL
            WHERE NOT EXISTS (
                SELECT 1 FROM job WHERE group_name = #{groupName} AND name = #{name}
            )
            """)
    int insertIfAbsent(Job job);
}
