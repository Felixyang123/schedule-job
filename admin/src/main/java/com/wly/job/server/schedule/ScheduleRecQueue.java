package com.wly.job.server.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.dao.rep.ScheduleRecRep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduleRecQueue implements SmartLifecycle {

    private static final int BATCH_SIZE = 500;

    private static final long POLL_TIMEOUT_MS = 200;

    private static final long GRACEFUL_SHUTDOWN_WAIT_MS = 2000;

    private final ScheduleRecRep recRep;

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();

    private volatile boolean running = false;

    private ExecutorService saveExecutor;

    public void save(ScheduleRec rec) {
        queue.offer(new SaveTask(rec));
    }

    public void markSuccess(String requestId, String executeResult) {
        mark(requestId, ScheduleRec.SUCCESS, executeResult);
    }

    public void markFail(String requestId, String executeResult) {
        mark(requestId, ScheduleRec.FAIL, executeResult);
    }

    private void mark(String requestId, int status, String executeResult) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        queue.offer(new UpdateTask(requestId, status, executeResult));
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

    private void flush(List<SaveTask> saveBatch, List<UpdateTask> updateBatch) {
        if (!saveBatch.isEmpty()) {
            try {
                recRep.saveBatch(saveBatch.stream().map(SaveTask::rec).toList());
            } catch (Exception e) {
                log.error("save schedule recs fail: ", e);
            }
            saveBatch.clear();
        }

        for (UpdateTask task : updateBatch) {
            try {
                ScheduleRec rec = ScheduleRec.builder()
                        .status(task.status())
                        .executeResult(task.executeResult())
                        .completeTime(new Date())
                        .build();
                recRep.update(rec, Wrappers.<ScheduleRec>lambdaUpdate().eq(ScheduleRec::getRequestId, task.requestId()));
            } catch (Exception e) {
                log.error("update schedule rec fail, requestId: {}", task.requestId(), e);
            }
        }
        updateBatch.clear();
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

    private sealed interface Task permits SaveTask, UpdateTask {
    }

    private record SaveTask(ScheduleRec rec) implements Task {
    }

    private record UpdateTask(String requestId, int status, String executeResult) implements Task {
    }
}
