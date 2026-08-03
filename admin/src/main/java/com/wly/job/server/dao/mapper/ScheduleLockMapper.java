package com.wly.job.server.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 单行选主锁（schedule_lock，id=1）CAS 操作，见 ADR-0004。
 */
public interface ScheduleLockMapper {

    @Insert("INSERT IGNORE INTO schedule_lock (id) VALUES (1)")
    int ensureLockRow();

    @Update("""
            UPDATE schedule_lock
            SET owner = #{owner}, expire_time = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)
            WHERE id = 1 AND (owner = #{owner} OR expire_time IS NULL OR expire_time < NOW())
            """)
    int acquireOrRenew(@Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE schedule_lock
            SET owner = NULL, expire_time = NULL
            WHERE id = 1 AND owner = #{owner}
            """)
    int release(@Param("owner") String owner);
}
