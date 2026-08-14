package com.wly.job.server.config;

import com.wly.config.core.client.RegistryClient;
import com.wly.config.core.client.RegistryHelper;
import com.wly.config.core.config.RegistryClientProps;
import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.NetworkUtils;
import com.wly.job.server.client.NettyLifecycle;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.mapper.ScheduleLockMapper;
import com.wly.job.server.ha.AlwaysLeaderElection;
import com.wly.job.server.ha.DbLeaderElection;
import com.wly.job.server.ha.LeaderElection;
import com.wly.job.server.ha.RedisLeaderElection;
import com.wly.job.server.registry.DefaultInstanceRegistry;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.registry.RemoteRegisterCenterRegistry;
import com.wly.job.server.schedule.ScheduleServiceTemplate;
import com.wly.job.server.schedule.engine.DelayQueueSchedulerEngine;
import com.wly.job.server.schedule.engine.SchedulerEngine;
import com.wly.job.server.schedule.engine.TimeWheelSchedulerEngine;
import com.wly.job.server.stroage.JobInstancePersistStorage;
import com.wly.job.server.stroage.LocalCacheJobInstanceStorage;
import com.wly.job.server.stroage.RedisJobInstanceStorage;
import com.wly.job.server.stroage.RefreshJobInstanceStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 调度相关 Bean 的装配配置。
 *
 * <p>按 {@code schedule.*} 配置键选择具体实现：
 * <ul>
 *   <li>{@code schedule.registry}：DEFAULT（默认，本地实例存储）→ {@link DefaultInstanceRegistry}；
 *       CENTER → {@link RemoteRegisterCenterRegistry}（接入外部注册中心）。</li>
 *   <li>{@code schedule.service}：DEFAULT（默认，按作业名发现）→ {@link ScheduleServiceTemplate}；
 *       GROUP → 同类型按作业分组发现（注入不同 discoveryKey 提取函数）。</li>
 *   <li>{@code schedule.engine}：DELAY_QUEUE（默认）→ {@link DelayQueueSchedulerEngine}；
 *       TIME_WHEEL → {@link TimeWheelSchedulerEngine}。</li>
 *   <li>{@code schedule.refreshStorage}：LOCAL / REDIS，选择持久化 + 缓存的两级实例存储组合
 *       （RefreshJobInstanceStorage）。</li>
 *   <li>{@code schedule.ha.enabled=true} 时装配真实选主实现（DB / REDIS，按
 *       {@code schedule.ha.election} 切换，owner 默认 host:port），否则回退恒主
 *       {@link AlwaysLeaderElection}。</li>
 * </ul>
 */
@Configuration
public class ScheduleConfiguration {

    /** LOCAL 缓存的两级实例存储（持久化 + 本地缓存，后台回灌） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "refreshStorage", havingValue = "LOCAL")
    public RefreshJobInstanceStorage refreshLocalCacheJobInstanceStorage(JobInstancePersistStorage persistStorage,
                                                               LocalCacheJobInstanceStorage cacheStorage) {
        return new RefreshJobInstanceStorage(persistStorage, cacheStorage);
    }

    /** Redis 缓存的两级实例存储（持久化 + Redis 缓存，后台回灌） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "refreshStorage", havingValue = "REDIS")
    public RefreshJobInstanceStorage refreshRedisJobInstanceStorage(JobInstancePersistStorage persistStorage,
                                                               RedisJobInstanceStorage cacheStorage) {
        return new RefreshJobInstanceStorage(persistStorage, cacheStorage);
    }

    /** 默认派发服务：按作业名发现选执行器（默认，匹配缺失时生效） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "DEFAULT", matchIfMissing = true)
    public ScheduleServiceTemplate defaultScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer,
                                                          Registry registry, ScheduleProps props) {
        return new ScheduleServiceTemplate(client, loadBalancer, registry, props, Job::getName);
    }

    /** 分组发现派发服务：按作业分组名发现执行器（GROUP 模式） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "service", havingValue = "GROUP")
    public ScheduleServiceTemplate groupNameDiscoveryScheduleService(ScheduleJobClient client, LoadBalancer loadBalancer,
                                                                     Registry registry, ScheduleProps props) {
        return new ScheduleServiceTemplate(client, loadBalancer, registry, props, Job::getGroupName);
    }

    /** 默认实例注册中心：本地实例存储（默认，匹配缺失时生效） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "registry", havingValue = "DEFAULT", matchIfMissing = true)
    public DefaultInstanceRegistry defaultInstanceRegistry(JobInstancePersistStorage storage, ScheduleProps props) {
        return new DefaultInstanceRegistry(storage, props);
    }

    /** 远程注册中心式实例注册中心（CENTER 模式，跨机房间享实例） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "registry", havingValue = "CENTER")
    public RemoteRegisterCenterRegistry remoteRegisterCenterRegistry(RegistryHelper registryHelper,
                                                                     RegistryClient registryClient,
                                                                     RegistryClientProps registryClientProps,
                                                                     ScheduleProps scheduleProps) {
        return new RemoteRegisterCenterRegistry(registryHelper, registryClient, registryClientProps, scheduleProps);
    }

    /** 默认调度引擎：JDK DelayQueue（默认，匹配缺失时生效） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "engine", havingValue = "DELAY_QUEUE", matchIfMissing = true)
    public SchedulerEngine delayQueueSchedulerEngine() {
        return new DelayQueueSchedulerEngine();
    }

    /** 时间轮调度引擎（TIME_WHEEL 模式） */
    @Bean
    @ConditionalOnProperty(prefix = "schedule", name = "engine", havingValue = "TIME_WHEEL")
    public SchedulerEngine timeWheelSchedulerEngine() {
        return new TimeWheelSchedulerEngine();
    }

    /** 未显式配置调度引擎时的兜底（默认 DelayQueue），保证 SchedulerEngine 单例可用 */
    @Bean
    @ConditionalOnMissingBean(SchedulerEngine.class)
    public SchedulerEngine fallbackSchedulerEngine() {
        return new DelayQueueSchedulerEngine();
    }

    /**
     * HA 开关开启时装配真实选主实现：按 {@code schedule.ha.election} 选择 DB 租约锁（默认）或 Redis 锁；
     * owner 节点 ID 默认取 {@code host:port}（可通过 {@code schedule.ha.instance-id} 显式指定）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "schedule.ha", name = "enabled", havingValue = "true")
    public LeaderElection leaderElection(ScheduleLockMapper lockMapper, StringRedisTemplate redisTemplate,
                                         ScheduleProps props, @Value("${server.port:8100}") int port) {
        String election = StringUtils.hasText(props.getHaElection())
                ? props.getHaElection().trim().toUpperCase()
                : "DB";
        String owner = StringUtils.hasText(props.getHaInstanceId())
                ? props.getHaInstanceId()
                : NetworkUtils.getServerIp() + ":" + port;
        if ("REDIS".equals(election)) {
            return new RedisLeaderElection(redisTemplate, owner, props.getHaLeaseSeconds());
        }
        return new DbLeaderElection(lockMapper, owner, props.getHaLeaseSeconds());
    }

    /** HA 未开启时回退恒主选主实现（isLeader 恒为 true） */
    @Bean
    @ConditionalOnMissingBean(LeaderElection.class)
    public LeaderElection alwaysLeaderElection() {
        return new AlwaysLeaderElection();
    }

    /**
     * RPC 回调线程池（有界）：{@code schedule.callback-threads} 个线程 + 容量 1024 的有界队列。
     * 队列满时由 {@link ScheduleFuture} 捕获 {@code RejectedExecutionException} 降级为当前线程直接执行，
     * 保证回调不丢失（At-Least-Once 闭环关键步骤）。
     * 生命周期：{@code destroyMethod="shutdown"} 兜底；实际停机顺序由 {@code NettyLifecycle} 统一编排。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService callbackExecutor(ScheduleProps props) {
        int threads = props.getCallbackThreads();
        if (threads < 1) {
            throw new IllegalArgumentException(
                    "schedule.callback-threads must be >= 1, got: " + threads);
        }
        // MdcExecutorService.wrap：回调提交自动透传 requestId 等 MDC 上下文（Spec 2026-08-06 §2.3）
        return MdcExecutorService.wrap(new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024), r -> {
                    Thread thread = new Thread(r, NettyLifecycle.CALLBACK_POOL_NAME);
                    thread.setDaemon(true);
                    return thread;
                }));
    }
}
