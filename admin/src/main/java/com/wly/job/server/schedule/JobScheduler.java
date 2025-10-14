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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobScheduler implements SmartLifecycle {
    private final JobRep jobRep;

    private final ScheduleService scheduleService;

    private final ScheduleRecRep scheduleRecRep;

    private volatile boolean running = true;

    private final LinkedBlockingQueue<ScheduleRec> scheduleRecQueue = new LinkedBlockingQueue<>();

    private final DelayQueue<ScheduleJob> scheduleQueue = new DelayQueue<>();

    private ExecutorService buildScheduleJobsExecutor;
    private ExecutorService saveRecExecutor;
    private ExecutorService scheduleJobsExecutor;

    public record ScheduleJob(Job job, long expireNanos) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return expireNanos - getNanos();
        }

        private long getNanos() {
            Instant instant = Instant.now();
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        }

        public static void main(String[] args) {
            System.out.println(System.nanoTime());
        }

        @Override
        public int compareTo(Delayed o) {
            if (o == this) {
                return 0;
            }
            if (o instanceof ScheduleJob other) {
                long diff = expireNanos - other.expireNanos;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return 1;
                } else {
                    return 0;
                }
            }
            long d = (getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS));
            return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
        }

        public static ScheduleJob of(Job job) {
            return new ScheduleJob(job, CronUtils.getNextExecutionNanos(job.getCron()));
        }
    }


    @Override
    public void start() {
        asyncBuildScheduleJobs();

        asyncScheduleJobs();

        asyncSaveScheduleRecs();
    }

    private void asyncBuildScheduleJobs() {
        buildScheduleJobsExecutor = Executors.newSingleThreadExecutor();
        buildScheduleJobsExecutor.execute(() -> {
            long offset = 0;
            while (running) {
                try {
                    long start = System.currentTimeMillis();

                    List<Job> jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
                    while (!CollectionUtils.isEmpty(jobs)) {
                        List<ScheduleJob> scheduleJobs = jobs.stream().map(ScheduleJob::of).toList();
                        scheduleQueue.addAll(scheduleJobs);
                        // 刷新offset
                        offset = jobs.getLast().getId();
                        jobs = jobRep.batchQueryJobsByCursor(offset, 1000);
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

    private void asyncScheduleJobs() {
        scheduleJobsExecutor = Executors.newSingleThreadExecutor();
        scheduleJobsExecutor.execute(() -> {
            while (running || !scheduleQueue.isEmpty()) {
                try {
                    Job job = scheduleQueue.take().job;
                    // 重新加入队列
                    scheduleQueue.add(ScheduleJob.of(job));

                    String requestId = UUID.randomUUID().toString().replace("-", "");
                    ScheduleRec scheduleRec = ScheduleRec.builder()
                            .jobId(job.getId())
                            .requestId(requestId)
                            .executeParam(job.getExecuteParam())
                            .scheduleTime(new Date())
                            .status(ScheduleRec.PENDING)
                            .build();
                    scheduleRecRep.save(scheduleRec);
                    try {
                        scheduleService.schedule(requestId, job);
                    } catch (Exception e) {
                        ScheduleRec updateRec = ScheduleRec.builder().id(scheduleRec.getId()).completeTime(new Date())
                                .status(ScheduleRec.FAIL).executeResult(e.getMessage()).build();
                        scheduleRecRep.updateById(updateRec);
                        throw e;
                    }
                } catch (Exception e) {
                    log.error("schedule job queue take error: ", e);
                }
            }
        });
    }

    @Override
    public void stop() {
        this.running = false;
        this.buildScheduleJobsExecutor.shutdownNow();
        this.scheduleJobsExecutor.shutdownNow();
        this.saveRecExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
