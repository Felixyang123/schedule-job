package com.wly.job.server.ha;

import com.wly.job.server.config.ScheduleProps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 选主循环：后台线程按 poll 间隔轮询，Leader 按 renew 间隔续约；
 * isLeader() 为 1s 级内存标志，派发前检查（ADR-0004 决策 #6）。
 * 监听器用 ObjectProvider 延迟收集：Spring 构造本组件时不会触发 JobScheduler 创建，
 * 避免构造注入环；start() 时再解析全部 LeadershipListener Bean。
 */
@Slf4j
@Component
public class ScheduleLeaderElector implements SmartLifecycle {

    private final LeaderElection leaderElection;

    private final ScheduleProps props;

    private final List<LeadershipListener> listeners = new CopyOnWriteArrayList<>();

    private ObjectProvider<LeadershipListener> listenerProvider;

    private volatile boolean leader = false;

    private volatile boolean running = false;

    private boolean listenersLoaded = false;

    private long lastRenewNanos;

    private Thread thread;

    @Autowired
    public ScheduleLeaderElector(LeaderElection leaderElection, ScheduleProps props,
                                 ObjectProvider<LeadershipListener> listenerProvider) {
        this.leaderElection = leaderElection;
        this.props = props;
        this.listenerProvider = listenerProvider;
    }

    /**
     * 测试用构造：直接提供监听器。
     */
    ScheduleLeaderElector(LeaderElection leaderElection, ScheduleProps props,
                          List<LeadershipListener> listeners) {
        this.leaderElection = leaderElection;
        this.props = props;
        this.listeners.addAll(listeners);
    }

    public boolean isLeader() {
        return leader;
    }

    public void addListener(LeadershipListener listener) {
        loadedListeners().add(listener);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        loadedListeners();
        if (!props.isHaEnabled()) {
            // 单节点/HA 关闭：恒为主，行为与现状一致
            setLeader(true);
            return;
        }
        thread = new Thread(this::loop, "schedule-leader-elector");
        thread.setDaemon(true);
        thread.start();
        log.info("ScheduleLeaderElector started, election: {}", props.getHaElection());
    }

    private void loop() {
        while (running) {
            try {
                tick();
                Thread.sleep(props.getHaPollSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Leader election loop error", e);
            }
        }
    }

    /**
     * 单次选举周期（package-private 供测试直接驱动）：
     * 已是主节点时按 renew 间隔续约，续约失败即降级为 Standby；
     * 非主节点时尝试抢锁，成功即升级为 Leader 并通知全部监听器。
     */
    void tick() {
        long now = System.nanoTime();
        if (leader) {
            if (now - lastRenewNanos >= props.getHaRenewSeconds() * 1_000_000_000L) {
                if (!leaderElection.acquireOrRenew()) {
                    log.warn("Leader lease expired, step down");
                    setLeader(false);
                } else {
                    lastRenewNanos = now;
                }
            }
        } else if (leaderElection.acquireOrRenew()) {
            log.info("Become leader, instance: {}", props.getHaInstanceId());
            lastRenewNanos = now;
            setLeader(true);
        }
    }

    private List<LeadershipListener> loadedListeners() {
        if (!listenersLoaded) {
            listenersLoaded = true;
            if (listenerProvider != null) {
                listenerProvider.orderedStream().forEach(listeners::add);
            }
        }
        return listeners;
    }

    /**
     * 变更主/Standby 状态并分发监听器：仅在状态真正翻转时通知一次，
     * 由监听器（如 JobScheduler）据此重建/清空本地调度视图。
     */
    private void setLeader(boolean newLeader) {
        if (leader == newLeader) {
            return;
        }
        leader = newLeader;
        List<LeadershipListener> snapshot = loadedListeners();
        if (newLeader) {
            snapshot.forEach(LeadershipListener::onBecomeLeader);
        } else {
            snapshot.forEach(LeadershipListener::onLoseLeadership);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (leader) {
            leaderElection.release();
            setLeader(false);
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 最先启动、最后停止，保证 JobScheduler.start() 读取 isLeader 时已就绪
        return Integer.MIN_VALUE;
    }
}
