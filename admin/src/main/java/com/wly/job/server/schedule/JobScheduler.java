package com.wly.job.server.schedule;

import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.utils.CronUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobScheduler implements SmartLifecycle {
    private final JobRep jobRep;

    private final ScheduleService scheduleService;

    private final ScheduleRecRep scheduleRecRep;

    private volatile boolean running = true;

    private final LinkedBlockingQueue<Job> scheduleJobQueue = new LinkedBlockingQueue<>();

    private final LinkedBlockingQueue<ScheduleRec> scheduleRecQueue = new LinkedBlockingQueue<>();

    private ExecutorService scheduleJobExecutor;
    private ExecutorService saveRecExecutor;
    private ExecutorService refreshRunTimeExecutor;


    @Override
    public void start() {
        asyncScheduleJobs();

        asyncSaveScheduleRecs();

        asyncUpdateJobsNextRunTime();
    }

    private void asyncScheduleJobs() {
        scheduleJobExecutor = Executors.newSingleThreadExecutor();
        scheduleJobExecutor.execute(() -> {
            while (running) {
                try {
                    long start = System.currentTimeMillis();
                    long nextRunTime = start / 1000;
                    long offset = 0;

                    List<Job> jobs = jobRep.batchQueryNextRunJobsFromOffset(nextRunTime, offset, 1000);
                    while (!CollectionUtils.isEmpty(jobs)) {
                        scheduleJobQueue.addAll(jobs);
                        // 执行任务
                        for (Job job : jobs) {
                            String requestId = scheduleService.schedule(job);
                            ScheduleRec scheduleRec = ScheduleRec.builder()
                                    .jobId(job.getId())
                                    .requestId(requestId)
                                    .executeParam(job.getExecuteParam())
                                    .scheduleTime(new Date())
                                    .status(ScheduleRec.PENDING)
                                    .build();
                            scheduleRecQueue.add(scheduleRec);
                        }

                        offset = jobs.getLast().getId();
                        jobs = jobRep.batchQueryNextRunJobsFromOffset(nextRunTime, offset, 1000);
                    }

                    long end = System.currentTimeMillis();
                    long sleepTime = 1000 - (end - start);
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                } catch (Exception e) {
                    log.error("job scheduler error: ", e);
                }
            }
        });
    }

    private void asyncSaveScheduleRecs() {
        saveRecExecutor = Executors.newSingleThreadExecutor();
        saveRecExecutor.execute(() -> {
            List<ScheduleRec> scheduleRecs = new ArrayList<>();
            while (running || !scheduleRecs.isEmpty()) {
                ScheduleRec rec = scheduleRecQueue.peek();
                if (rec == null) {
                    try {
                        rec = scheduleRecQueue.poll(1000, TimeUnit.MILLISECONDS);
                        if (rec != null) {
                            scheduleRecs.add(rec);
                        }
                        scheduleRecRep.saveBatch(scheduleRecs);
                        scheduleRecs.clear();
                    } catch (InterruptedException e) {
                        log.error("schedule rec queue poll error: ", e);
                    }
                } else {
                    rec = scheduleRecQueue.poll();
                    scheduleRecs.add(rec);
                    if (scheduleRecs.size() >= 500) {
                        scheduleRecRep.saveBatch(scheduleRecs);
                        scheduleRecs.clear();
                    }
                }
            }
        });
    }

    private void asyncUpdateJobsNextRunTime() {
        refreshRunTimeExecutor = Executors.newSingleThreadExecutor();
        refreshRunTimeExecutor.execute(() -> {
            List<Job> scheduleJobs = new ArrayList<>();
            while (running || !scheduleJobs.isEmpty()) {
                Job job = scheduleJobQueue.peek();
                if (job == null) {
                    try {
                        job = scheduleJobQueue.poll(1000, TimeUnit.MILLISECONDS);
                        if (job != null) {
                            scheduleJobs.add(job);
                        }
                        updateNextRunTime(scheduleJobs);
                        scheduleJobs.clear();
                    } catch (InterruptedException e) {
                        log.error("schedule job queue poll error: ", e);
                    }
                } else {
                    job = scheduleJobQueue.poll();
                    scheduleJobs.add(job);
                    if (scheduleJobs.size() >= 500) {
                        updateNextRunTime(scheduleJobs);
                        scheduleJobs.clear();
                    }
                }
            }
        });
    }

    private void updateNextRunTime(List<Job> jobs) {
        Date date = new Date();
        for (Job job : jobs) {
            long nextRunTimeSec = CronUtils.getNextExecutionSecond(job.getCron());
            job.setNextRunTime(nextRunTimeSec);
            job.setUpdateTime(date);
            job.setUpdater("scheduler");
        }
        jobRep.updateBatchById(jobs);
    }

    @Override
    public void stop() {
        this.running = false;
        this.scheduleJobExecutor.shutdownNow();
        this.refreshRunTimeExecutor.shutdownNow();
        this.saveRecExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
