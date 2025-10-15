package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Instance;
import org.apache.ibatis.annotations.Insert;

public interface InstanceMapper extends BaseMapper<Instance> {

    @Insert("""
            insert into instance(`name`, `host`, `port`, `status`, `expire_time`, `create_time`, `update_time`, `creator`, `updater`)
            values(#{name}, #{host}, #{port}, #{status}, #{expireTime}, #{createTime}, #{updateTime}, #{creator}, #{updater})
            on duplicate key update `status`=#{status}, `expire_time`=#{expireTime}, `update_time`=#{updateTime}, `updater`=#{updater}
            """)
    int saveOrUpdate(Instance instance);
}
