package com.wly.job.starter.processor;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.utils.NetworkUtils;
import com.wly.job.core.ScheduleJobCoreFactory;
import com.wly.job.core.invocation.InnerJob;
import com.wly.job.core.invocation.MethodInvocationJob;
import com.wly.job.core.registry.RemoteJobRegistry;
import com.wly.job.starter.annotation.ScheduleJob;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ReflectionUtils;

import java.util.Date;
import java.util.concurrent.*;

@RequiredArgsConstructor
public class ScheduleJobAnnotationProcessor implements BeanPostProcessor, SmartLifecycle {
    private final ScheduleJobCoreFactory factory;

    private volatile boolean running = true;

    private final ConcurrentMap<String, JobInfo> jobInfosMap = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<InnerJob> jobs = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            ScheduleJob scheduleJob = method.getAnnotation(ScheduleJob.class);
            if (scheduleJob != null) {
                method.setAccessible(true);
                MethodInvocationJob job = new MethodInvocationJob(method, bean, scheduleJob.name());
                jobs.add(job);

                JobInfo jobInfo = JobInfo.builder()
                        .instance(JobInstance.builder()
                                .discoveryName(scheduleJob.name())
                                .port(factory.getPort())
                                .host(NetworkUtils.getServerIp())
                                .expireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * 3000L))
                                .build())
                        .cron(scheduleJob.cron())
                        .jobname(scheduleJob.name())
                        .description(scheduleJob.description())
                        .type(scheduleJob.type().getCode())
                        .strategy(scheduleJob.strategy().getCode())
                        .executeParam(scheduleJob.executeParam())
                        .group(factory.getGroup())
                        .build();
                jobInfosMap.putIfAbsent(scheduleJob.name(), jobInfo);
            }
        });
        return bean;
    }

    @Override
    public void start() {
        DelayQueue<JobInstanceRegisterTask> instanceDelayQueue = new DelayQueue<>();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.execute(() -> {
                for (InnerJob job : jobs) {
                    if (factory.getInnerJobRegistry().register(job)) {
                        JobInfo jobInfo = jobInfosMap.get(job.jobname());
                        instanceDelayQueue.put(new JobInstanceRegisterTask(jobInfo.getInstance(), factory.getRemoteJobRegistry(), System.nanoTime() + factory.getHeartbeatInterval() * 1000000000L));
                        factory.getRemoteJobRegistry().register(jobInfo);
                    }
                }

                while (running) {
                    try {
                        JobInstanceRegisterTask registerTask = instanceDelayQueue.take();
                        registerTask.run();
                        registerTask.getJobInstance().setExpireTime(new Date(System.currentTimeMillis() + factory.getHeartbeatInterval() * 3000L));
                        instanceDelayQueue.put(new JobInstanceRegisterTask(registerTask.getJobInstance(), factory.getRemoteJobRegistry(), System.nanoTime() + factory.getHeartbeatInterval() * 1000000000L));
                    } catch (InterruptedException e) {
                       Thread.currentThread().interrupt();
                    }
                }
            });
        }
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @AllArgsConstructor
    public static class JobInstanceRegisterTask implements Runnable, Delayed {
        @Getter
        private final JobInstance jobInstance;

        private final RemoteJobRegistry registry;

        /**
         * 下次注册的时间纳秒数
         */
        private long registerTimeNanos;

        @Override
        public void run() {
            registry.register(jobInstance);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return registerTimeNanos - System.nanoTime();
        }

        @Override
        public int compareTo(Delayed o) {
            long d = (getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS));
            return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
        }
    }
}
