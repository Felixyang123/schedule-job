package com.wly.job.starter.processor;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.logging.MdcExecutorService;
import com.wly.job.common.utils.NetworkUtils;
import com.wly.job.common.utils.ThreadPoolUtils;
import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.invocation.MethodInvocationJob;
import com.wly.job.core.registry.RemoteJobRegistry;
import com.wly.job.starter.annotation.ScheduleJob;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ReflectionUtils;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.*;

/**
 * 调度注解处理器：Worker 侧生命周期核心管理器，实现 BeanPostProcessor 与 SmartLifecycle。
 * <p>
 * 初始化期（postProcessAfterInitialization）：扫描 Spring Bean 中标注 {@code @ScheduleJob}
 * 的方法，包装为 {@link MethodInvocationJob} 并注册进本地任务注册表；同时构造
 * {@link JobInfo}/{@link JobInstance} 缓存（instance 的 discoveryKey 在组模式开启时取任务组名，
 * 否则取作业名），并把作业与 instanceKey 的关联存入 {@code JobRegistration}。
 * <p>
 * 启动期（start）：由单线程执行器按序完成三段（Spec 2026-08-12 注册解耦）：
 * ① 注册各 JobInstance（HTTP /open/job/instance/register），消除作业已注册但首次心跳未到时的派发空窗；
 * ② 注册本地任务并远程注册作业元数据（HTTP /open/job/register），实例注册失败不阻断本步；
 * ③ 将各 JobInstance 包装为 {@link JobInstanceRegisterTask} 投递 DelayQueue，主循环 take() 到期后
 * 发送心跳；无论本次心跳成功或失败，均刷新租约并重新投递，实现周期续约与失败自愈。
 * <p>
 * 停止期（stop）：置 running=false 结束心跳循环，对执行器先温和关闭、超 2s 强制中断，
 * 最后关闭核心工厂（Netty RPC 服务）。核心工厂关闭后不可重启，因此同一处理器实例调用
 * {@code stop()} 后再次 {@code start()} 将抛出 {@link IllegalStateException}。
 */
@RequiredArgsConstructor
@Slf4j
public class ScheduleJobAnnotationProcessor implements BeanPostProcessor, SmartLifecycle {
    private final ScheduleJobCoreFactory factory;

    /**
     * 心跳过期宽限倍数：租约到期时间 = 心跳间隔(毫秒) * 此倍数（心跳 × 3）
     */
    private static final long HEARTBEAT_EXPIRY_MULTIPLIER = 3000L;

    /** 注册/心跳线程池名：线程工厂与停机日志共用，保证 jstack 线程名与日志一致 */
    private static final String POOL_NAME = "job-register-renew";

    private volatile boolean running = false;

    private volatile boolean stopped = false;

    private final ConcurrentMap<String, JobRegistration> jobRegistrationMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, JobInstance> jobInstanceMap = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<InnerJob> jobs = new CopyOnWriteArrayList<>();

    private ExecutorService registerAndRenewTaskExecutor;

    private final DelayQueue<JobInstanceRegisterTask> instanceDelayQueue = new DelayQueue<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            ScheduleJob scheduleJob = method.getAnnotation(ScheduleJob.class);
            if (scheduleJob != null) {
                // 私有方法反射调用需先解除访问限制
                method.setAccessible(true);
                MethodInvocationJob job = new MethodInvocationJob(method, bean, scheduleJob.name(), factory.getInvocationHooks());
                jobs.add(job);

                // 租约到期时间 = 心跳间隔(毫秒) * 3，作为心跳续约的过期宽限
                JobInstance instance = JobInstance.builder()
                        .port(factory.getPort())
                        .host(NetworkUtils.getServerIp())
                        // 凭证身份（ADR-0006）：随心跳/注册请求上报，Admin 拦截器校验后按鉴权结果写版本
                        .applicationName(factory.getApplicationName())
                        .env(factory.getEnv())
                        .expireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * HEARTBEAT_EXPIRY_MULTIPLIER))
                        .build();
                if (Boolean.TRUE.equals(factory.getEnableGroup())) {
                    // 组模式：同应用作业共享同一 discoveryKey（application-name），注册为同一实例
                    instance.setDiscoveryKey(factory.getApplicationName());
                } else {
                    instance.setDiscoveryKey(scheduleJob.name());
                }

                JobInfo jobInfo = JobInfo.builder()
                        .cron(scheduleJob.cron())
                        .jobname(scheduleJob.name())
                        .description(scheduleJob.description())
                        .type(scheduleJob.type().getCode())
                        .strategy(scheduleJob.strategy().getCode())
                        .executeParam(scheduleJob.executeParam())
                        .group(factory.getApplicationName())
                        .build();
                // instanceKey 与本作业绑定保存：作业注册时用它选择 Admin 节点，
                // 避免在启动逻辑里重新推导 discoveryKey 规则（组模式/默认模式差异）
                jobRegistrationMap.putIfAbsent(scheduleJob.name(),
                        new JobRegistration(jobInfo, instance.getInstanceKey()));

                jobInstanceMap.putIfAbsent(instance.getDiscoveryKey(), instance);
            }
        });
        return bean;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (stopped) {
            throw new IllegalStateException("ScheduleJobAnnotationProcessor cannot restart after stop");
        }
        this.running = true;
        instanceDelayQueue.clear();
        registerAndRenewTaskExecutor = MdcExecutorService.wrap(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, POOL_NAME);
            thread.setDaemon(true);
            return thread;
        }));
        // 心跳/注册循环为常驻系统线程，无业务链路 requestId（HTTP 请求侧由 RequestLogFilter 生成），
        // 由 MdcExecutorService 透传快照（装饰但不强制 MDC，Spec 2.3 包装点④），保证与未来虚拟线程化兼容
        registerAndRenewTaskExecutor.execute(() -> {
            // ① 先注册实例：消除「作业已注册但首次心跳未到」窗口内派发无可用执行器的问题。
            // jobInstanceMap 以 discoveryKey 为键，组模式下同组 N 个作业只注册一次实例。
            for (JobInstance jobInstance : jobInstanceMap.values()) {
                try {
                    factory.getRemoteJobRegistry().register(jobInstance);
                } catch (RuntimeException e) {
                    log.warn("Initial instance register failed, continue startup, discoveryKey: {}",
                            jobInstance.getDiscoveryKey(), e);
                }
            }

            // ② 再注册作业：单个实例注册失败不阻断，由步骤 ③ 的心跳周期自愈
            for (InnerJob job : jobs) {
                if (factory.getInnerJobRegistry().register(job)) {
                    JobRegistration registration = jobRegistrationMap.get(job.jobname());
                    if (registration != null) {
                        factory.getRemoteJobRegistry()
                                .register(registration.jobInfo(), registration.instanceKey());
                    }
                }
            }

            // ③ 进入周期心跳续约
            jobInstanceMap.values().forEach(jobInstance -> instanceDelayQueue.put(
                    new JobInstanceRegisterTask(jobInstance, factory.getRemoteJobRegistry(), factory.getHeartbeatInterval())));

            while (running) {
                try {
                    // 阻塞等待最近到期的实例；单次心跳失败仅影响本次调用，任务仍会重新投递并在后续周期自愈
                    JobInstanceRegisterTask registerTask = instanceDelayQueue.take();
                    try {
                        registerTask.run();
                    } catch (RuntimeException e) {
                        log.warn("Instance heartbeat failed, will retry, discoveryKey: {}",
                                registerTask.getJobInstance().getDiscoveryKey(), e);
                    } finally {
                        if (running) {
                            registerTask.getJobInstance().setExpireTime(new Date(System.currentTimeMillis()
                                    + factory.getHeartbeatInterval() * HEARTBEAT_EXPIRY_MULTIPLIER));
                            instanceDelayQueue.put(new JobInstanceRegisterTask(registerTask.getJobInstance(),
                                    factory.getRemoteJobRegistry(), factory.getHeartbeatInterval()));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        log.info("start schedule job core");
    }

    int pendingHeartbeatTaskCount() {
        return instanceDelayQueue.size();
    }

    @Override
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        this.stopped = true;
        this.running = false;
        ThreadPoolUtils.shutdownGracefully(registerAndRenewTaskExecutor, POOL_NAME, 2, TimeUnit.SECONDS);
        instanceDelayQueue.clear();
        factory.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 作业注册上下文（Worker 进程内部结构，不参与 RPC）：把作业元数据与本 Worker 的
     * {@code instanceKey} 成对保存。{@code instanceKey} 仅用于注册时选择 Admin 节点，
     * 保证同一 Worker 的作业注册与实例注册在 HASH 策略下命中同一节点。
     */
    private record JobRegistration(JobInfo jobInfo, String instanceKey) {
    }

    static class JobInstanceRegisterTask implements Runnable, Delayed {
        @Getter
        private final JobInstance jobInstance;

        private final RemoteJobRegistry registry;

        /**
         * 下次注册的时间纳秒数
         */
        private final long registerTimeNanos;

        JobInstanceRegisterTask(JobInstance jobInstance, RemoteJobRegistry registry, long heartbeatInterval) {
            this.jobInstance = jobInstance;
            this.registry = registry;
            this.registerTimeNanos = getNanos() + heartbeatInterval * 1000000000L;
        }

        @Override
        public void run() {
            registry.register(jobInstance);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(registerTimeNanos - getNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            if (o == this) {
                return 0;
            }

            if (o instanceof JobInstanceRegisterTask jobInstanceRegisterTask) {
                return Long.compare(registerTimeNanos, jobInstanceRegisterTask.registerTimeNanos);
            }

            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }

        private long getNanos() {
            Instant instant = Instant.now();
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        }

    }
}
