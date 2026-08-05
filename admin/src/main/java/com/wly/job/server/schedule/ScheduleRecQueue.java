package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import com.wly.job.server.metrics.MetricsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 调度执行记录异步批量落库队列。
 * <p>
 * 所有 {@link ScheduleRec} 的写入与按 requestId 的结果回写都通过本队列串行化：
 * 先入队的 INSERT 一定先被消费，避免"结果更新先于记录插入"的竞态；
 * 同时通过攒批 {@code saveBatch()} 避免高频调度时逐条写库阻塞调度主循环。
 *
 * <p>可靠性设计（Spec §2.5）：落库失败不丢批——{@code saveBatch} / {@code update} 失败后
 * 退避重试（最多 3 次，间隔 100/500/1000ms），仍失败仅 {@code log.error} 并累加
 * {@code job.rec.save.failure} 指标（Task G 接入）；内部队列有界（10000），打满后丢弃新入队记录
 * 并 {@code log.warn}，累加 {@code job.rec.dropped}，绝不阻塞、不反压派发主链路。
 */
@Component
@Slf4j
public class ScheduleRecQueue implements SmartLifecycle {

    private static final int BATCH_SIZE = 500;

    private static final long POLL_TIMEOUT_MS = 200;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000;

    /**
     * 内部队列容量上限：打满后丢弃新入队记录并记日志，绝不阻塞派发主链路。
     */
    private static final int QUEUE_CAPACITY = 10000;

    /**
     * 落库失败后的重试次数上限（首次尝试之外再重试 3 次），重试间隔见 {@link #RETRY_BACKOFF_MS}。
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 重试退避间隔（毫秒）：第 i 次重试前等待 {@code RETRY_BACKOFF_MS[i]}。
     */
    private static final long[] RETRY_BACKOFF_MS = {100, 500, 1000};

    private final ScheduleRecRep recRep;

    private final BlockingQueue<Task> queue;

    /** 可观测性指标封装（Spec §2.7）：落库失败 / 丢弃计数由 Micrometer Counter 承载 */
    private final MetricsRegistry metrics;

    private volatile boolean running = false;

    private ExecutorService saveExecutor;

    /** Spring 注入构造（@Autowired 显式指定，配合包私有测试构造避免多构造器歧义） */
    @Autowired
    public ScheduleRecQueue(ScheduleRecRep recRep, MetricsRegistry metrics) {
        this(recRep, new LinkedBlockingQueue<>(QUEUE_CAPACITY), metrics);
    }

    /** 测试构造：注入定容队列以直接验证打满丢弃行为 */
    ScheduleRecQueue(ScheduleRecRep recRep, BlockingQueue<Task> queue, MetricsRegistry metrics) {
        this.recRep = recRep;
        this.queue = queue;
        this.metrics = metrics;
    }

    /**
     * 入队一条调度记录插入任务（在回调线程调用，立即返回，落库由消费线程攒批完成）。
     */
    public void save(ScheduleRec rec) {
        if (!queue.offer(new SaveTask(rec))) {
            metrics.counter(MetricsRegistry.JOB_REC_DROPPED).increment();
            log.warn("ScheduleRec queue full (capacity={}), drop new save record, jobId: {}",
                    QUEUE_CAPACITY, rec.getJobId());
        }
    }

    /** 入队"按 requestId 将调度记录回写为成功"的更新任务 */
    public void markSuccess(String requestId, String executeResult) {
        mark(requestId, ScheduleRec.SUCCESS, executeResult);
    }

    /** 入队"按 requestId 将调度记录回写为失败"的更新任务 */
    public void markFail(String requestId, String executeResult) {
        mark(requestId, ScheduleRec.FAIL, executeResult);
    }

    /**
     * 入队更新任务：更新依赖 requestId 匹配既有的调度记录行（INSERT 先于 UPDATE 消费，
     * 由队列串行顺序保证）。requestId 为空时直接忽略，避免产生无法关联的脏更新。
     */
    private void mark(String requestId, int status, String executeResult) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        if (!queue.offer(new UpdateTask(requestId, status, executeResult))) {
            metrics.counter(MetricsRegistry.JOB_REC_DROPPED).increment();
            log.warn("ScheduleRec queue full (capacity={}), drop new update record, requestId: {}",
                    QUEUE_CAPACITY, requestId);
        }
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        this.running = true;
        saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "schedule-rec-save");
            thread.setDaemon(true);
            return thread;
        });
        saveExecutor.execute(this::consume);
        log.info("ScheduleRecQueue started.");
    }

    /**
     * 消费主循环：攒批收集 SaveTask/UpdateTask，达到 BATCH_SIZE 或 poll 超时时落库；
     * 停机（running=false）时先 drain 队列剩余任务再 flush，保证优雅停机不丢记录。
     */
    private void consume() {
        List<SaveTask> saveBatch = new ArrayList<>();
        List<UpdateTask> updateBatch = new ArrayList<>();
        while (running || !queue.isEmpty()) {
            try {
                Task task = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (task instanceof SaveTask saveTask) {
                    saveBatch.add(saveTask);
                } else if (task instanceof UpdateTask updateTask) {
                    updateBatch.add(updateTask);
                }
                // task == null 表示poll超时，立即保存当前任务，然后开始下一次阻塞监听
                if (task == null || saveBatch.size() + updateBatch.size() >= BATCH_SIZE) {
                    flush(saveBatch, updateBatch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flush(saveBatch, updateBatch);
                break;
            }
        }
        drainRest(saveBatch, updateBatch);
        flush(saveBatch, updateBatch);
    }

    private void drainRest(List<SaveTask> saveBatch, List<UpdateTask> updateBatch) {
        Task task;
        while ((task = queue.poll()) != null) {
            if (task instanceof SaveTask saveTask) {
                saveBatch.add(saveTask);
            } else if (task instanceof UpdateTask updateTask) {
                updateBatch.add(updateTask);
            }
        }
    }

    void flush(List<SaveTask> saveBatch, List<UpdateTask> updateBatch) {
        if (!saveBatch.isEmpty() || !updateBatch.isEmpty()) {
            log.debug("schedule rec flush, save={}, update={}", saveBatch.size(), updateBatch.size());
        }
        if (!saveBatch.isEmpty()) {
            flushSaveBatch(saveBatch);
            saveBatch.clear();
        }

        for (UpdateTask task : updateBatch) {
            flushUpdate(task);
        }
        updateBatch.clear();
    }

    /**
     * 批量插入落库：失败不丢批，整体退避重试 {@link #MAX_RETRIES} 次（批次内顺序保持不变），
     * 仍失败 {@code log.error} + 计数累加后放弃，由调用方 clear 批次继续消费循环。
     */
    private void flushSaveBatch(List<SaveTask> saveBatch) {
        List<ScheduleRec> recs = saveBatch.stream().map(SaveTask::rec).toList();
        for (int attempt = 0; ; attempt++) {
            try {
                recRep.saveBatch(recs);
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("save schedule recs fail, retry {}/{}: ", attempt + 1, MAX_RETRIES, e);
                    sleepBackoff(RETRY_BACKOFF_MS[attempt]);
                } else {
                    metrics.counter(MetricsRegistry.JOB_REC_SAVE_FAILURE).increment();
                    log.error("save schedule recs fail after {} retries, {} records dropped: ",
                            MAX_RETRIES, recs.size(), e);
                    return;
                }
            }
        }
    }

    /**
     * 单条更新落库：失败只重试当前这一行（不重放已成功的行），最多 {@link #MAX_RETRIES} 次，
     * 仍失败 {@code log.error} + 计数累加后跳过，继续处理下一条。
     */
    private void flushUpdate(UpdateTask task) {
        ScheduleRec rec = ScheduleRec.builder()
                .status(task.status())
                .executeResult(task.executeResult())
                .completeTime(new Date())
                .build();
        for (int attempt = 0; ; attempt++) {
            try {
                recRep.update(rec, Wrappers.<ScheduleRec>lambdaUpdate().eq(ScheduleRec::getRequestId, task.requestId()));
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("update schedule rec fail, requestId: {}, retry {}/{}: ",
                            task.requestId(), attempt + 1, MAX_RETRIES, e);
                    sleepBackoff(RETRY_BACKOFF_MS[attempt]);
                } else {
                    // update 重试耗尽同样计入"落库失败"指标（saveBatch/update 统一口径，Spec §2.7）
                    metrics.counter(MetricsRegistry.JOB_REC_SAVE_FAILURE).increment();
                    log.error("update schedule rec fail after {} retries, requestId: {}: ",
                            MAX_RETRIES, task.requestId(), e);
                    return;
                }
            }
        }
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 停机中被打断：复位中断标志并继续，下次 poll 会立即感知中断退出
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        this.running = false;
        if (saveExecutor == null) {
            return;
        }
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ScheduleRecQueue stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    sealed interface Task permits SaveTask, UpdateTask {
    }

    record SaveTask(ScheduleRec rec) implements Task {
    }

    record UpdateTask(String requestId, int status, String executeResult) implements Task {
    }
}
