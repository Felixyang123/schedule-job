package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Instance;
import org.apache.ibatis.annotations.Insert;

/**
 * 执行器实例表（instance）MyBatis-Plus Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；另提供基于唯一键 upsert 的实例注册/心跳刷新
 * （{@link #saveOrUpdate}），实现幂等的"存在则更新、不存在则插入"。
 */
public interface InstanceMapper extends BaseMapper<Instance> {

    /**
     * 幂等保存执行器实例：按 name+host+port 唯一键，存在则更新在线状态与心跳到期时间，
     * 不存在则插入新行。用于执行器心跳上报路径。
     *
     * @param instance 执行器实例信息
     * @return 影响行数
     */
    @Insert("""
            insert into instance(`name`, `host`, `port`, `application_name`, `env`, `credential_version`,
                                 `status`, `expire_time`, `create_time`, `update_time`, `creator`, `updater`)
            values(#{name}, #{host}, #{port}, #{applicationName}, #{env}, #{credentialVersion},
                   #{status}, #{expireTime}, #{createTime}, #{updateTime}, #{creator}, #{updater})
            on duplicate key update `status`=#{status}, `expire_time`=#{expireTime},
                                   `application_name`=#{applicationName}, `env`=#{env},
                                   `credential_version`=#{credentialVersion},
                                   `update_time`=#{updateTime}, `updater`=#{updater}
            """)
    int saveOrUpdate(Instance instance);
}
