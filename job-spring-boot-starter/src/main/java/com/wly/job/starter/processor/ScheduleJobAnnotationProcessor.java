package com.wly.job.starter.processor;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.utils.NetworkUtils;
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

@RequiredArgsConstructor
@Slf4j
public class ScheduleJobAnnotationProcessor implements BeanPostProcessor, SmartLifecycle {
    private final ScheduleJobCoreFactory factory;

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
                method.setAccessible(true);
                MethodInvocationJob job = new MethodInvocationJob(method, bean, scheduleJob.name(), factory.getInvocationHooks());
                jobs.add(job);

                JobInstance instance = JobInstance.builder()
                        .port(factory.getPort())
                        .host(NetworkUtils.getServerIp())
                        .expireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * 3000L))
                        .build();
                if (Boolean.TRUE.equals(factory.getEnableGroup())) {
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
        registerAndRenewTaskExecutor = Executors.newSingleThreadExecutor();
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
                    JobInstanceRegisterTask registerTask = instanceDelayQueue.take();
                    registerTask.run();
                    registerTask.getJobInstance().setExpireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * 3000L));
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
        if (registerAndRenewTaskExecutor != null) {
            registerAndRenewTaskExecutor.shutdown();
            try {
                if (!registerAndRenewTaskExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    registerAndRenewTaskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                registerAndRenewTaskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        factory.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public static class JobInstanceRegisterTask implements Runnable, Delayed {
        @Getter
        private final JobInstance jobInstance;

        private final RemoteJobRegistry registry;

        /**
         * 下次注册的时间纳秒数
         */
        private final long registerTimeNanos;

        public JobInstanceRegisterTask(JobInstance jobInstance, RemoteJobRegistry registry, long heartbeatInterval) {
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
