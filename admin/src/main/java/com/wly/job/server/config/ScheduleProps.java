package com.wly.job.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 调度中心（Admin）核心配置项，前缀 {@code schedule.*}。
 *
 * <p>承载调度引擎、派发线程数、HA 单活选主、实例注册开关等配置。参见 §0.4 关键配置键：
 * registry（DEFAULT / 注册中心）、service（DEFAULT / GROUP）、engine（DELAY_QUEUE / TIME_WHEEL）、
 * dispatch-threads（派发 worker 线程数，按 jobId 分片）、ha.*（选主 / 租约 / 清扫参数）、
 * admin.*（管控登录，Spec 2026-08-14 §3）。
 *
 * <p><b>绑定约束</b>：嵌套子属性（如 {@code schedule.ha.enabled}、{@code schedule.admin.username}）
 * 必须以嵌套类承载（{@link Ha} / {@link Admin}）——扁平字段无法接收多段点号属性
 * （如 {@code schedule.admin.username} 绑不到名为 {@code adminUsername} 的扁平字段）。
 */
@ConfigurationProperties(prefix = "schedule")
@Configuration
@Data
public class ScheduleProps {

    /**
     * 调度请求超时时间（毫秒）
     */
    private long reqTimeout = 30000;

    /**
     * 实例注册器类型（DEFAULT / CENTER）
     */
    private String registry;

    /**
     * 实例注册开关：为 true 时执行器心跳才写入注册中心 / 存储（默认 null 视为关闭）
     */
    private Boolean enableRegisterInstance;

    /**
     * 派发服务模式
     * DEFAULT（按作业发现键）
     * GROUP（按作业分组发现）
     */
    private String service;

    /**
     * 两级实例存储的缓存层类型
     * REDIS
     * LOCAL
     */
    private String refreshStorage;

    /**
     * 调度引擎
     * DELAY_QUEUE
     * TIME_WHEEL
     */
    private String engine = "DELAY_QUEUE";

    /**
     * 调度派发 worker 线程数（按 jobId 分片），默认 1
     */
    private int dispatchThreads = 1;

    /**
     * RPC 回调线程池线程数（schedule.callback-threads），默认 4；
     * 队列容量固定 1024，队列满时回调降级为当前线程直接执行（不丢回调）。
     */
    private int callbackThreads = 4;

    /**
     * 凭证种子配置（schedule.credential-seed，ADR-0006）：逗号分隔的 {@code app:env:明文} 列表，
     * 启动时幂等创建 ACTIVE v1 凭证。未配置时 /open/** 一律 401（Fail-Closed，延续旧版
     * 「未配置即拒绝」语义）。明文仅在种子中出现，数据库只存 PBKDF2 摘要。
     */
    private String credentialSeed;

    /**
     * 管控后台登录（schedule.admin.*，Spec 2026-08-14 §3）：配置化单用户，不建用户表，无 RBAC。
     */
    private Admin admin = new Admin();

    /**
     * schedule_rec 保留天数（schedule.rec-retention-days），默认 7。
     * 每日清理超过保留期的终态记录（FAIL/SUCCESS）；RUNNING（非终态）永不删除，
     * 对账/清扫逻辑依赖它在途状态判定（Spec §2.10 / 验收标准 10）。
     */
    private int recRetentionDays = 7;

    /**
     * HA 单活（schedule.ha.*，ADR-0004）：多 Admin 部署时开启选主；租约 / 续约 / 轮询 / 清扫参数。
     */
    private Ha ha = new Ha();

    /**
     * 管控后台登录（Spec 2026-08-14 §3）。
     */
    @Data
    public static class Admin {

        /**
         * 登录用户名（schedule.admin.username），默认 admin。
         */
        private String username = "admin";

        /**
         * 登录密码（schedule.admin.password），明文配置（与 credential-seed 同一交付模型，
         * 经环境变量/配置分发，不落库）。<b>强制配置</b>：未配置时 /admin/** 一律 401（Fail-Closed）。
         */
        private String password;
    }

    /**
     * HA 单活选主参数（ADR-0004）。
     */
    @Data
    public static class Ha {

        /**
         * HA 单活模式开关（schedule.ha.enabled），默认 false
         */
        private boolean enabled = false;

        /**
         * 选主实现（schedule.ha.election）: DB（默认）/ REDIS
         */
        private String election = "DB";

        /**
         * 租约时长（schedule.ha.lease-seconds，秒），默认 10
         */
        private long leaseSeconds = 10;

        /**
         * 续约间隔（schedule.ha.renew-seconds，秒），默认 3
         */
        private long renewSeconds = 3;

        /**
         * 选主轮询间隔（schedule.ha.poll-seconds，秒），默认 1
         */
        private long pollSeconds = 1;

        /**
         * 陈旧 RUNNING 记录清扫间隔（schedule.ha.stale-sweep-seconds，秒），默认 30
         */
        private long staleSweepSeconds = 30;

        /**
         * 节点唯一 ID（schedule.ha.instance-id，默认 host:port，由 ScheduleConfiguration 组装）
         */
        private String instanceId;
    }
}
