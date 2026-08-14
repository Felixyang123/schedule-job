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
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `name`               VARCHAR(128) NOT NULL COMMENT '发现键（任务名或分组名）',
    `host`               VARCHAR(64)  NOT NULL COMMENT '执行器 IP',
    `port`               INT          NOT NULL COMMENT '执行器 Netty 端口',
    `application_name`   VARCHAR(128) DEFAULT NULL COMMENT '凭证身份：应用名（ADR-0006）',
    `env`                VARCHAR(64)  DEFAULT NULL COMMENT '凭证身份：环境（ADR-0006）',
    `credential_version` INT          DEFAULT NULL COMMENT '该实例所用凭证版本（服务端按鉴权结果写入）',
    `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '0: 下线 1: 在线',
    `expire_time`        DATETIME     NOT NULL COMMENT '心跳过期时间',
    `create_time`        DATETIME     DEFAULT NULL,
    `update_time`        DATETIME     DEFAULT NULL,
    `creator`            VARCHAR(64)  DEFAULT NULL,
    `updater`            VARCHAR(64)  DEFAULT NULL,
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
    KEY `idx_schedule_time` (`schedule_time`),
    KEY `idx_status_complete_time_id` (`status`, `complete_time`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='调度执行记录表';

-- =====================================================================
-- Admin 单活 HA 选主锁表（ADR-0004）
-- 单行（id=1）CAS 抢锁/续约；owner 为节点唯一 ID（默认 host:port，可配置覆盖）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `schedule_lock` (
    `id`          BIGINT       NOT NULL,
    `owner`       VARCHAR(128) DEFAULT NULL COMMENT '当前持有者（节点唯一 ID）',
    `expire_time` DATETIME     DEFAULT NULL COMMENT '租约过期时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Admin 选主锁表（单行 id=1）';

INSERT IGNORE INTO `schedule_lock` (`id`) VALUES (1);

-- =====================================================================
-- 作业变更源（ADR-0005）：调度队列增量对账
-- 稳态成本 ∝ 变更量；change_type 仅供排查，消费端以 job_id 回查当前行为准。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `job_change` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `job_id`      BIGINT       NOT NULL COMMENT '作业 ID',
    `change_type` TINYINT      NOT NULL COMMENT '1注册 2编辑 3启停 4单次完成 5删除 6失败重试',
    `operator`    VARCHAR(64)  DEFAULT NULL COMMENT '操作人（管理端用户名或 system）',
    `request_id`  VARCHAR(64)  DEFAULT NULL COMMENT '调度追踪 ID（单次完成/失败重试时关联 ScheduleRec）',
    `job_name`    VARCHAR(128) DEFAULT NULL COMMENT '冗余作业名，便于排查',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='作业变更源（增量对账消费）';

-- =====================================================================
-- 凭证体系（ADR-0006）：按「应用身份 + 环境」发放凭证
-- credential 只存身份与状态指针；credential_version 存 PBKDF2 摘要与审计，
-- 进入 REVOKED/CANCELED 后由应用清空 token_hash/salt；credential_change 仅作缓存失效信号。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `credential` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `application_name` VARCHAR(128) NOT NULL COMMENT '应用身份（Worker 配置 schedule-job.application-name）',
    `env`              VARCHAR(64)  NOT NULL COMMENT '环境（Worker 从 activeProfiles 提取，无则 default）',
    `active_version`   INT          DEFAULT NULL COMMENT '当前生效版本号（指针，NULL 无 active）',
    `pending_version`  INT          DEFAULT NULL COMMENT '待激活版本号（指针，NULL 无 pending）',
    `create_time`      DATETIME     DEFAULT NULL,
    `creator`          VARCHAR(64)  DEFAULT NULL,
    `update_time`      DATETIME     DEFAULT NULL,
    `updater`          VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_env` (`application_name`, `env`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='凭证身份表';

CREATE TABLE IF NOT EXISTS `credential_version` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `credential_id`      BIGINT       NOT NULL COMMENT '所属凭证身份 ID',
    `version`            INT          NOT NULL COMMENT '版本号（身份内自增，状态机指针引用）',
    `status`             TINYINT      NOT NULL COMMENT '0 PENDING 1 ACTIVE 2 REVOKED 3 CANCELED（单向流转）',
    `token_hash`         VARCHAR(128) DEFAULT NULL COMMENT 'PBKDF2 摘要（Base64，派生密钥；吊销/取消后清空）',
    `salt`               VARCHAR(64)  DEFAULT NULL COMMENT '派生盐（Base64，吊销/取消后清空）',
    `iterations`         INT          NOT NULL DEFAULT 120000 COMMENT 'PBKDF2 迭代次数',
    `masked_token`       VARCHAR(64)  DEFAULT NULL COMMENT '脱敏展示值（如 def****oken，永久保留）',
    `expire_time`        DATETIME     NOT NULL COMMENT '有效期截止（过期判定依据本列，不得用缓存 TTL 代替）',
    `prepared_by`        VARCHAR(64)  DEFAULT NULL,
    `create_time`        DATETIME     DEFAULT NULL,
    `activated_by`       VARCHAR(64)  DEFAULT NULL,
    `activate_time`      DATETIME     DEFAULT NULL,
    `forced_activation`  TINYINT      DEFAULT NULL COMMENT '是否强制激活（就绪校验未过时置 1）',
    `activation_reason`  VARCHAR(256) DEFAULT NULL,
    `revoked_by`         VARCHAR(64)  DEFAULT NULL,
    `revoke_time`        DATETIME     DEFAULT NULL,
    `revoke_reason`      VARCHAR(256) DEFAULT NULL,
    `canceled_by`        VARCHAR(64)  DEFAULT NULL,
    `cancel_time`        DATETIME     DEFAULT NULL,
    `cancel_reason`      VARCHAR(256) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_credential_version` (`credential_id`, `version`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='凭证版本表（版本 + 历史 + 审计兼任）';

CREATE TABLE IF NOT EXISTS `credential_change` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `application_name` VARCHAR(128) NOT NULL COMMENT '应用身份',
    `env`              VARCHAR(64)  NOT NULL COMMENT '环境',
    `change_type`      TINYINT      NOT NULL COMMENT '1 PREPARE 2 ACTIVATE 3 CANCEL 4 REVOKE',
    `operator`         VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_app_env` (`application_name`, `env`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='凭证变更源（仅缓存失效信号，不携带密码材料）';

-- =====================================================================
-- 存量库升级（新建库无需执行：上方 CREATE TABLE 已含该索引）
-- ---------------------------------------------------------------------
-- schedule_rec 保留策略清理依赖 (status, complete_time) 过滤，缺索引会全表扫描。
-- MySQL 8 的 ADD INDEX 不支持 IF NOT EXISTS：若索引已存在会报 1061 Duplicate key name，
-- 属预期结果，可安全忽略；确认后再执行。
ALTER TABLE `schedule_rec`
    ADD INDEX `idx_status_complete_time_id` (`status`, `complete_time`, `id`);

-- 存量修复（ADR-0005）：Finished 仅在单次任务上有效（finished=1 ⇒ type=1）
UPDATE `job` SET `finished` = 0 WHERE `type` = 0 AND `finished` = 1;
