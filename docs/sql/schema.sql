-- =====================================================================
-- Job 分布式任务调度框架建表脚本 (MySQL 8.x)
-- 说明：
--   job.instance 的唯一键 uk_name_host_port 是 InstanceMapper.saveOrUpdate
--   （ON DUPLICATE KEY UPDATE）能够正确 upsert 的前提；
--   job 的唯一键 uk_group_name_name 用于执行器重复注册时幂等去重。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `job_group` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(128) NOT NULL COMMENT '分组名称',
    `description` VARCHAR(512) DEFAULT NULL,
    `create_time` DATETIME     DEFAULT NULL,
    `creator`     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='任务分组';

CREATE TABLE IF NOT EXISTS `job` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `group_name`    VARCHAR(128) NOT NULL COMMENT '任务分组名',
    `name`          VARCHAR(128) NOT NULL COMMENT '任务唯一标识名称',
    `description`   VARCHAR(512) DEFAULT NULL,
    `execute_param` VARCHAR(2048) DEFAULT NULL COMMENT '执行参数',
    `cron`          VARCHAR(128) NOT NULL COMMENT 'Cron 调度表达式',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '0: 停止 1: 运行',
    `type`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0: 普通任务 1: 单次任务',
    `strategy`      TINYINT      NOT NULL DEFAULT 1 COMMENT '路由策略: 1随机 2轮询 3哈希',
    `finished`      TINYINT      NOT NULL DEFAULT 0 COMMENT '单次任务完成标记: 0未完成 1已完成',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    `create_time`   DATETIME     DEFAULT NULL,
    `update_time`   DATETIME     DEFAULT NULL,
    `creator`       VARCHAR(64)  DEFAULT NULL,
    `updater`       VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_name_name` (`group_name`, `name`),
    KEY `idx_status_id` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='作业元数据表';

CREATE TABLE IF NOT EXISTS `instance` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(128) NOT NULL COMMENT '发现键（任务名或分组名）',
    `host`        VARCHAR(64)  NOT NULL COMMENT '执行器 IP',
    `port`        INT          NOT NULL COMMENT '执行器 Netty 端口',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '0: 下线 1: 在线',
    `expire_time` DATETIME     NOT NULL COMMENT '心跳过期时间',
    `create_time` DATETIME     DEFAULT NULL,
    `update_time` DATETIME     DEFAULT NULL,
    `creator`     VARCHAR(64)  DEFAULT NULL,
    `updater`     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_host_port` (`name`, `host`, `port`),
    KEY `idx_name_status_expire` (`name`, `status`, `expire_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='执行器实例表';

CREATE TABLE IF NOT EXISTS `schedule_rec` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `job_id`         BIGINT       NOT NULL COMMENT '作业 ID',
    `request_id`     VARCHAR(64)  NOT NULL COMMENT '调度链路追踪 ID',
    `execute_param`  VARCHAR(2048) DEFAULT NULL,
    `execute_result` TEXT COMMENT '执行返回结果 JSON',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '-1: 失败 0: 运行中 1: 成功',
    `schedule_time`  DATETIME     NOT NULL,
    `complete_time`  DATETIME     DEFAULT NULL,
    `operator`       VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_job_id` (`job_id`),
    KEY `idx_schedule_time` (`schedule_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='调度执行记录表';
