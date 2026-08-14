package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.ScheduleRec;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 调度执行记录表（schedule_rec）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；调度记录的写入通过 ScheduleRecQueue 异步攒批
 * saveBatch 落库，保障不阻塞主调度循环。
 */
public interface ScheduleRecMapper extends BaseMapper<ScheduleRec> {

    /**
     * 按批删除过期终态记录，避免单条大 DELETE 长时间持锁/产生大事务。
     * 子查询仅取 {@code batchSize} 个主键；外层按主键删除，兼容 MySQL 对同表 DELETE 子查询的限制。
     *
     * <p>不加 {@code ORDER BY id}：删除哪一批过期行对结果无影响，而
     * {@code idx_status_complete_time_id} 中 id 仅在各 {@code (status, complete_time)} 前缀内有序，
     * 按 id 全局排序会对整个匹配集 filesort，积压量大时代价随之放大。
     *
     * @return 本批删除行数；小于 batchSize 表示已清完
     */
    @Delete("""
            DELETE FROM schedule_rec
            WHERE id IN (
                SELECT id FROM (
                    SELECT id
                    FROM schedule_rec
                    WHERE status IN (-1, 1)
                      AND complete_time < #{cutoff}
                    LIMIT #{batchSize}
                ) expired
            )
            """)
    int deleteExpiredTerminalBatch(@Param("cutoff") Date cutoff, @Param("batchSize") int batchSize);
}
