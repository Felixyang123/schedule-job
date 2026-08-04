package com.wly.job.server.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 单行选主锁（schedule_lock，id=1）CAS 操作，见 ADR-0004。
 */
public interface ScheduleLockMapper {

    /**
     * 确保锁行存在：INSERT IGNORE 保证集群内任意节点首次初始化时只插入一次 id=1 的锁行。
     *
     * @return 1 表示新插入锁行，0 表示已存在
     */
    @Insert("INSERT IGNORE INTO schedule_lock (id) VALUES (1)")
    int ensureLockRow();

    /**
     * CAS 原子抢占 / 续约租约锁：
     * 仅当锁行无持有者（owner 为空或租约已过期）或持有者即为自身时更新持有者与租约到期时间。
     * 该原子性保证同一时刻最多一个节点成功获取 / 维持主节点身份。
     *
     * @param owner        持锁节点唯一 ID
     * @param leaseSeconds 租约时长（秒），到期后锁可被其他节点抢占
     * @return 1 表示抢占 / 续约成功，0 表示失败
     */
    @Update("""
            UPDATE schedule_lock
            SET owner = #{owner}, expire_time = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)
            WHERE id = 1 AND (owner = #{owner} OR expire_time IS NULL OR expire_time < NOW())
            """)
    int acquireOrRenew(@Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    /**
     * 释放租约锁：仅当持有者确为自身时才清空 owner 与 expire_time，防止误释放其他节点的锁。
     *
     * @param owner 持锁节点唯一 ID
     * @return 1 表示释放成功，0 表示非自身持有
     */
    @Update("""
            UPDATE schedule_lock
            SET owner = NULL, expire_time = NULL
            WHERE id = 1 AND owner = #{owner}
            """)
    int release(@Param("owner") String owner);
}
