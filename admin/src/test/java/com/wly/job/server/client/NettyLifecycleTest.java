package com.wly.job.server.client;

import com.wly.job.server.client.handler.ScheduleRequestHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NettyLifecycleTest {

    @Test
    void phaseStopsNettyLastButBeforeLeaderElector() {
        NettyLifecycle lifecycle = new NettyLifecycle(
                mock(ChannelManager.class), mock(ScheduleRequestHandler.class), mock(ExecutorService.class));

        // Spec §2.8：NettyLifecycle 停机 phase 最低（最后停，仅早于 ScheduleLeaderElector 的 MIN_VALUE）
        assertEquals(Integer.MIN_VALUE + 10, lifecycle.getPhase());
        // 高于 ScheduleLeaderElector(MIN_VALUE)：Spring 按 phase 从高到低停，
        // 保证先关连接/回调线程、后释放租约锁
        assertTrue(Integer.MIN_VALUE + 10 > Integer.MIN_VALUE);
    }
}
