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
 * 否则取作业名）。
 * <p>
 * 启动期（start）：由单线程执行器依次完成 ① 注册本地任务并远程注册作业元数据到 Admin
 * （HTTP /open/job/register）；② 将各 JobInstance 包装为 {@link JobInstanceRegisterTask} 投递到
 * DelayQueue；③ 主循环 take() 到期心跳任务，发送心跳（HTTP /open/job/instance/register）后
 * 更新租约到期时间并重新投递，实现周期续约。
 * <p>
 * 停止期（stop）：置 running=false 结束心跳循环，对执行器先温和关闭、超 2s 强制中断，
 * 最后关闭核心工厂（Netty RPC 服务）。
 */
@RequiredArgsConstructor
@Slf4j
public class ScheduleJobAnnotationProcessor implements BeanPostProcessor, SmartLifecycle {
    private final ScheduleJobCoreFactory factory;

    /**
     * 心跳过期宽限倍数：租约到期时间 = 心跳间隔(毫秒) * 此倍数（心跳 × 3）
     */
    private static final long HEARTBEAT_EXPIRY_MULTIPLIER = 3000L;

    private volatile boolean running = false;

    private final ConcurrentMap<String, JobInfo> jobInfoMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, JobInstance> jobInstanceMap = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<InnerJob> jobs = new CopyOnWriteArrayList<>();

    private ExecutorService registerAndRenewTaskExecutor;

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
                        .expireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * HEARTBEAT_EXPIRY_MULTIPLIER))
                        .build();
                if (Boolean.TRUE.equals(factory.getEnableGroup())) {
                    // 组模式：同组作业共享同一 discoveryKey，注册为同一实例
                    instance.setDiscoveryKey(factory.getGroupName());
                } else {
                    instance.setDiscoveryKey(scheduleJob.name());
                }

                JobInfo jobInfo = JobInfo.builder()
                        .instance(instance)
                        .cron(scheduleJob.cron())
                        .jobname(scheduleJob.name())
                        .description(scheduleJob.description())
                        .type(scheduleJob.type().getCode())
                        .strategy(scheduleJob.strategy().getCode())
                        .executeParam(scheduleJob.executeParam())
                        .group(factory.getGroupName())
                        .build();
                jobInfoMap.putIfAbsent(scheduleJob.name(), jobInfo);

                jobInstanceMap.putIfAbsent(instance.getDiscoveryKey(), instance);
            }
        });
        return bean;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;
        DelayQueue<JobInstanceRegisterTask> instanceDelayQueue = new DelayQueue<>();
        registerAndRenewTaskExecutor = MdcExecutorService.wrap(Executors.newSingleThreadExecutor());
        // 心跳/注册循环为常驻系统线程，无业务链路 requestId（HTTP 请求侧由 RequestLogFilter 生成），
        // 由 MdcExecutorService 透传快照（装饰但不强制 MDC，Spec 2.3 包装点④），保证与未来虚拟线程化兼容
        registerAndRenewTaskExecutor.execute(() -> {
            for (InnerJob job : jobs) {
                if (factory.getInnerJobRegistry().register(job)) {
                    JobInfo jobInfo = jobInfoMap.get(job.jobname());
                    factory.getRemoteJobRegistry().register(jobInfo);
                }
            }

            jobInstanceMap.values().forEach(jobInstance -> instanceDelayQueue.put(
                    new JobInstanceRegisterTask(jobInstance, factory.getRemoteJobRegistry(), factory.getHeartbeatInterval())));

            while (running) {
                try {
                    // 阻塞等待最近到期的实例，触发心跳后刷新租约并重新投递，形成周期续约
                    JobInstanceRegisterTask registerTask = instanceDelayQueue.take();
                    registerTask.run();
                    registerTask.getJobInstance().setExpireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * HEARTBEAT_EXPIRY_MULTIPLIER));
                    instanceDelayQueue.put(new JobInstanceRegisterTask(registerTask.getJobInstance(), factory.getRemoteJobRegistry(), factory.getHeartbeatInterval()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        log.info("start schedule job core");
    }

    @Override
    public void stop() {
        this.running = false;
        ThreadPoolUtils.shutdownGracefully(registerAndRenewTaskExecutor, 2, TimeUnit.SECONDS);
        factory.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
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
