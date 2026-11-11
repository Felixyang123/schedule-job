package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.AdminSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminSessionMapper extends BaseMapper<AdminSession> {

    @Select("select * from admin_session where account_id = #{accountId} limit 1")
    AdminSession selectByAccountId(@Param("accountId") String accountId);

    @Delete("delete from admin_session where account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") String accountId);
}
