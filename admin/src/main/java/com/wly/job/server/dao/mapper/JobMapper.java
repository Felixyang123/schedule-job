package com.wly.job.server.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wly.job.server.dao.entity.Job;
import org.apache.ibatis.annotations.Insert;

public interface JobMapper extends BaseMapper<Job> {

    @Insert("""
            insert into job(`group_name`, `name`, `description`, `execute_param`, `cron`, `status`, `type`, `strategy`, `next_run_time`, `deleted`, `create_time`, `update_time`, `creator`, `updater`)
            values(#{groupName}, #{name}, #{description}, #{executeParam}, #{cron}, #{status}, #{type}, #{strategy}, #{nextRunTime}, #{deleted}, #{createTime}, #{updateTime}, #{creator}, #{updater})
            on duplicate key update `description`=#{description}, `execute_param`=#{executeParam}, `cron`=#{cron}, `status`=#{status},`type`=#{type}, `strategy`=#{strategy}, `next_run_time`=#{nextRunTime},
            `deleted`=#{deleted}, `update_time`=#{updateTime}, `updater`=#{updater}
            """)
    int upsert(Job job);
}
