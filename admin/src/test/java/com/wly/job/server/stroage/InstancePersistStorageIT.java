package com.wly.job.server.stroage;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.rep.InstanceRep;
import com.wly.job.server.schedule.JobScheduler;
import com.wly.job.server.schedule.ScheduleRecCleaner;
import com.wly.job.server.schedule.ScheduleRunRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例心跳 upsert 集成测试（真实 MySQL job_test 库）。
 *
 * <p>验证 {@code InstanceMapper.saveOrUpdate} 的语义（Spec 2026-08-12 §6 与
 * {@code JobInstancePersistStorage} Javadoc）：
 * <ul>
 *   <li>心跳刷新只更新 {@code status / expire_time / update_time / updater}，<b>不覆盖</b>首次注册时间；</li>
 *   <li>同实例重复上报不产生重复行（唯一键 upsert）；</li>
 *   <li>list 仅返回在线且未过期实例。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
class InstancePersistStorageIT {

    @MockitoBean
    private JobScheduler jobScheduler;

    @MockitoBean
    private ScheduleRunRecovery scheduleRunRecovery;

    @MockitoBean
    private ScheduleRecCleaner scheduleRecCleaner;

    @Autowired
    private JobInstancePersistStorage persistStorage;

    @Autowired
    private InstanceRep instanceRep;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 类级唯一发现键：并发 Maven 进程 / 并行 IT 互不干扰；8 位随机串足够短（instance.name 列宽 128）
    private static final String DISCOVERY_KEY = "it-inst-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM instance WHERE name = '" + DISCOVERY_KEY + "'");
    }

    private JobInstance instance(long expireOffsetSeconds) {
        return JobInstance.builder()
                .discoveryKey(DISCOVERY_KEY)
                .host("192.168.50.10")
                .port(8102)
                .status(Instance.ONLINE)
                .expireTime(new Date(System.currentTimeMillis() + expireOffsetSeconds * 1000L))
                .build();
    }

    @Test
    void heartbeatRefreshPreservesCreateTime() throws Exception {
        persistStorage.put(instance(30));

        List<Instance> rows = instanceRep.lambdaQuery()
                .eq(Instance::getName, DISCOVERY_KEY).list();
        assertEquals(1, rows.size(), "首次上报应插入一行");
        Instance first = rows.get(0);
        assertNotNull(first.getCreateTime());
        assertEquals("system", first.getCreator());
        Date firstCreateTime = first.getCreateTime();

        // 模拟下一轮心跳：提前 expireTime 且等待约 1.2s 保证 update_time 可区分
        TimeUnit.MILLISECONDS.sleep(1200);
        persistStorage.put(instance(60));

        Instance second = instanceRep.lambdaQuery()
                .eq(Instance::getName, DISCOVERY_KEY).one();
        assertEquals(firstCreateTime.getTime(), second.getCreateTime().getTime(),
                "心跳刷新不得覆盖首次注册时间");
        assertTrue(second.getExpireTime().after(first.getExpireTime()), "心跳应刷新到期时间");
        assertTrue(second.getUpdateTime().getTime() >= first.getUpdateTime().getTime(),
                "update_time 应随心跳刷新");
    }

    @Test
    void repeatedHeartbeatDoesNotCreateDuplicateRow() {
        persistStorage.put(instance(30));
        persistStorage.put(instance(30));
        persistStorage.put(instance(30));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instance WHERE name = ?", Integer.class, DISCOVERY_KEY);
        assertEquals(1, count, "同实例重复心跳按唯一键 upsert，不产生重复行");
    }

    @Test
    void listReturnsOnlyOnlineUnexpiredInstances() {
        // 两个不同实例键（port 不同）：一个在线未过期、一个在线已过期
        persistStorage.put(JobInstance.builder()
                .discoveryKey(DISCOVERY_KEY).host("192.168.50.10").port(8102)
                .status(Instance.ONLINE)
                .expireTime(new Date(System.currentTimeMillis() + 60_000))
                .build());
        persistStorage.put(JobInstance.builder()
                .discoveryKey(DISCOVERY_KEY).host("192.168.50.11").port(8103)
                .status(Instance.ONLINE)
                .expireTime(new Date(System.currentTimeMillis() - 10_000))
                .build());

        List<JobInstance> result = persistStorage.list(List.of(DISCOVERY_KEY));

        assertEquals(1, result.size(), "list 应过滤已过期实例");
        assertEquals("192.168.50.10", result.get(0).getHost(), "应只返回在线未过期实例");
    }

    @Test
    void removeMarksInstanceOffline() {
        persistStorage.put(instance(60));

        persistStorage.remove(instance(60));

        Instance row = instanceRep.lambdaQuery().eq(Instance::getName, DISCOVERY_KEY).one();
        assertEquals(Instance.OFFLINE, row.getStatus(), "remove 应将实例置为下线而非物理删除");
        assertTrue(persistStorage.list(List.of(DISCOVERY_KEY)).isEmpty(), "下线实例不应出现在发现结果");
    }
}
