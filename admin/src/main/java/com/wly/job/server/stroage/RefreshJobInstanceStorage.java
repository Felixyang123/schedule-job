package com.wly.job.server.stroage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.convert.JobBeanConverter;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 两级实例存储组合：持久化（instance 表）+ 缓存，支持后台定期回灌缓存。
 *
 * <p>读路径：优先查缓存，缓存未命中再查持久层，避免冷启动空缓存直接打穿 DB；
 * 写路径：同时写持久层与缓存，保持两级一致。后台 {@code refresh-instances-thread} 线程每 30s
 * 按 instance 主键游标增量拉取在线实例回灌缓存，使缓存收敛于持久层的最新状态。
 *
 * <p>线程生命周期由本类管理（daemon 线程）：单轮拉取/回灌异常被隔离记录，线程不退出，
 * 下一周期自动重试；{@link #stop} 置运行标志并中断线程等待退出。
 */
@Slf4j
@RequiredArgsConstructor
public class RefreshJobInstanceStorage implements RefreshStorage<JobInstance>, SmartLifecycle {

    /** 后台回灌周期（毫秒） */
    private static final long REFRESH_INTERVAL_MS = 30_000L;

    /** 停止时等待刷新线程退出的宽限（毫秒） */
    private static final long THREAD_JOIN_WAIT_MS = 2_000L;

    /** 持久层存储（instance 物理表） */
    private final JobInstancePersistStorage persistStorage;

    /**
     * 提供抽象接口，默认提供local map的实现
     * 可以实现guava、redis等其他缓存
     */
    private final Storage<JobInstance> cacheStorage;

    private volatile boolean running = true;

    private Thread refreshThread;

    /** 幂等写入：同时写持久层与缓存 */
    @Override
    public void put(JobInstance value) {
        persistStorage.put(value);
        cacheStorage.put(value);
    }

    /** 批量幂等写入：同时写持久层与缓存 */
    @Override
    public void putAll(Collection<JobInstance> values) {
        persistStorage.putAll(values);
        cacheStorage.putAll(values);
    }

    /** 注销实例：同时从持久层（置下线）与缓存移除 */
    @Override
    public void remove(JobInstance value) {
        persistStorage.remove(value);
        cacheStorage.remove(value);
    }

    /** 清空两级存储 */
    @Override
    public void clear() {
        persistStorage.clear();
        cacheStorage.clear();
    }

    /** 读路径：优先缓存，命中为空时回退持久层（防缓存冷启动打穿 DB） */
    @Override
    public List<JobInstance> list(Collection<String> keys) {
        List<JobInstance> instances = cacheStorage.list(keys);
        if (CollectionUtils.isEmpty(instances)) {
            instances = persistStorage.list(keys);
        }
        return instances;
    }

    @Override
    public boolean running() {
        return this.running;
    }

    /** 按 instance 主键游标增量拉取在线且未过期的实例，并推进游标 */
    @Override
    public List<JobInstance> newDataCollection(RefreshContext<JobInstance> refreshContext) {
        long cursor = Optional.of(refreshContext.getCursor()).orElse(0L);
        InstanceRep instanceRep = persistStorage.instanceRep();
        List<Instance> onlineInstances = instanceRep.list(Wrappers.<Instance>lambdaQuery().eq(Instance::getStatus, Instance.ONLINE)
                .ge(Instance::getExpireTime, new Date()).gt(Instance::getId, cursor));
        if (!CollectionUtils.isEmpty(onlineInstances)) {
            refreshContext.setNewDataCollection(onlineInstances.stream().map(JobBeanConverter::convert).collect(Collectors.toList()));
            refreshContext.setCursor(onlineInstances.getLast().getId());
        }
        return refreshContext.getNewDataCollection();
    }

    /** 将本批持久层数据回灌缓存 */
    @Override
    public void refresh(RefreshContext<JobInstance> refreshContext) {
        refreshContext.getNewDataCollection().forEach(cacheStorage::put);
    }

    /** 启动后台回灌线程（daemon，单轮异常隔离后进入下一周期） */
    @Override
    public void start() {
        if (refreshThread != null && refreshThread.isAlive()) {
            return;
        }
        this.running = true;
        refreshThread = new Thread(this::refreshLoop, "refresh-instances-thread");
        refreshThread.setDaemon(true);
        refreshThread.start();
        log.info("RefreshJobInstanceStorage started, refresh interval: {}ms", REFRESH_INTERVAL_MS);
    }

    private void refreshLoop() {
        while (running) {
            try {
                RefreshContext<JobInstance> refreshContext = new RefreshContext<>();
                List<JobInstance> newDataCollection = newDataCollection(refreshContext);
                while (!CollectionUtils.isEmpty(newDataCollection)) {
                    refresh(refreshContext);
                    newDataCollection = newDataCollection(refreshContext);
                }
            } catch (Exception e) {
                // 单轮异常不得终止线程（DB 抖动时循环自愈），记录后进入休眠下一周期重试
                log.warn("RefreshJobInstanceStorage refresh round failed, will retry next round.", e);
            }
            try {
                Thread.sleep(REFRESH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 优雅停止：置 running=false 并中断/等待刷新线程退出 */
    @Override
    public void stop() {
        this.running = false;
        Thread thread = this.refreshThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(THREAD_JOIN_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
