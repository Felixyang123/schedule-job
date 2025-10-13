package com.wly.job.server.client.future;

import com.wly.job.common.exception.ScheduleException;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC异步结果Future
 */
public class ScheduleFuture<T> extends CompletableFuture<T> {

    private final long createTime;
    @Setter
    private long timeout;

    @Setter
    @Getter
    private Channel channel;

    @Getter
    private final List<ScheduleCallable> callables;

    public ScheduleFuture(long timeout, Channel channel) {
        this.createTime = System.currentTimeMillis();
        this.timeout = timeout;
        this.channel = channel;
        this.callables = new ArrayList<>();
    }

    public void addCallable(ScheduleCallable callable) {
        this.callables.add(callable);
    }

    /**
     * 获取结果，支持超时
     */
    @Override
    public T get() {
        try {
            return super.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ScheduleException("Schedule timeout: ", e);
        } catch (Exception e) {
            throw new ScheduleException("Schedule error: ", e);
        }
    }

    /**
     * 获取结果，指定超时时间
     */
    public T get(long timeout, TimeUnit unit) {
        try {
            return super.get(timeout, unit);
        } catch (TimeoutException e) {
            throw new ScheduleException("Schedule timeout: ", e);
        } catch (Exception e) {
            throw new ScheduleException("Schedule error: ", e);
        }
    }

    /**
     * 检查是否已超时
     */
    public boolean isTimeout() {
        return System.currentTimeMillis() - createTime > timeout;
    }
}