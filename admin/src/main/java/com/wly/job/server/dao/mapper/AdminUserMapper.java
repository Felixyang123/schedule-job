package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.AdminUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("select * from admin_user where username = #{username} limit 1")
    AdminUser selectByUsername(@Param("username") String username);

    @Select("select * from admin_user where account_id = #{accountId} limit 1")
    AdminUser selectByAccountId(@Param("accountId") String accountId);
}
