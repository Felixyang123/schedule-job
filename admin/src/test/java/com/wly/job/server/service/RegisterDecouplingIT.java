package com.wly.job.server.service;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.mapper.JobMapper;
import com.wly.job.server.dao.rep.JobChangeRep;
import com.wly.job.server.dao.rep.JobRep;
import com.wly.job.server.schedule.JobScheduler;
import com.wly.job.server.schedule.ScheduleRecCleaner;
import com.wly.job.server.schedule.ScheduleRunRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * 注册解耦集成测试（真实 MySQL job_test 库，@ActiveProfiles("it")）。
 *
 * <p>覆盖单测无法触达的 DB 行为（Spec 2026-08-12 §8 验收标准）：
 * <ul>
 *   <li>条件插入的返回语义（首插 1 / 重复 0）与自增主键回填；</li>
 *   <li>逻辑删除行占用唯一键、Worker 重启不复活；</li>
 *   <li>字段超长等非唯一键错误向上抛出（不被误判为「已存在」）；</li>
 *   <li>注册解耦：作业注册不再写 instance 表。</li>
 * </ul>
 *
 * <p>测试库须预先初始化：见 docs/sql/schema.sql（1061 重复索引报错可忽略）。
 * 运行方式：{@code mvn -pl admin test -Dtest=*IT}（依赖本机 MySQL，不进默认 mvn test）。
 */
@SpringBootTest
@ActiveProfiles("it")
class RegisterDecouplingIT {

    @MockitoBean
    private JobScheduler jobScheduler;

    @MockitoBean
    private ScheduleRunRecovery scheduleRunRecovery;

    @MockitoBean
    private ScheduleRecCleaner scheduleRecCleaner;

    @Autowired
    private ScheduleJobService scheduleJobService;

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private JobRep jobRep;

    @MockitoSpyBean
    private JobChangeRep jobChangeRep;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 类级唯一前缀：并发 Maven 进程 / 并行 IT 互不干扰；8 位随机串足够短（group_name/name 列宽 128）
    private static final String PREFIX = "it-reg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String GROUP = PREFIX + "group";
    private static final String JOB_NAME = PREFIX + "job";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // 仅清理由本测试类唯一前缀创建的数据，避免并行 IT 互删。
        List<Long> jobIds = jdbcTemplate.queryForList(
                "SELECT id FROM job WHERE group_name LIKE ?", Long.class, PREFIX + "%");
        for (Long jobId : jobIds) {
            jdbcTemplate.update("DELETE FROM schedule_rec WHERE job_id = ?", jobId);
            jdbcTemplate.update("DELETE FROM job_change WHERE job_id = ?", jobId);
        }
        jdbcTemplate.update("DELETE FROM job_change WHERE job_name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM job WHERE group_name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM instance WHERE name LIKE ?", PREFIX + "%");
    }

    private JobInfo info() {
        return JobInfo.builder()
                .group(GROUP).jobname(JOB_NAME).cron("0/5 * * * * ?").type(0).strategy(1)
                .build();
    }

    @Test
    void insertIfAbsentReturns1AndBackfillsId() {
        Job job = jobFromInfo().init();

        int inserted = jobMapper.insertIfAbsent(job);

        assertEquals(1, inserted, "首次插入应返回 1");
        assertNotNull(job.getId(), "自增主键应回填");
        assertNotNull(jobRep.getById(job.getId()), "插入后应能查回");
    }

    @Test
    void insertIfAbsentReturns0ForDuplicate() {
        Job first = jobFromInfo().init();
        jobMapper.insertIfAbsent(first);

        Job second = jobFromInfo().init();
        int inserted = jobMapper.insertIfAbsent(second);

        assertEquals(0, inserted, "重复注册应返回 0（正常路径，无异常）");
        assertEquals(1, countJobs(), "表中应只有一行");
    }

    @Test
    void insertIfAbsentConcurrentRaceLeavesExactlyOneRow() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> concurrentRegister(ready, start)),
                    executor.submit(() -> concurrentRegister(ready, start)));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个注册线程应就绪，避免栅栏死锁");
            start.countDown();

            for (Future<Boolean> result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS), "并发注册调用均应正常完成");
            }
            assertEquals(1, countJobs(), "唯一键兜底后最终只能保留一行");
            assertEquals(1, countRegisterChanges(), "并发注册只应写一条 REGISTER 变更");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发测试线程池应及时退出");
        }
    }

    @Test
    void jobTableHasRequiredUniqueIndex() {
        String columns = jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'job'
                  AND index_name = 'uk_group_name_name'
                  AND non_unique = 0
                """, String.class);
        assertEquals("group_name,name", columns,
                "uk_group_name_name 应为按 group_name/name 顺序组成的唯一索引");
    }

    private boolean concurrentRegister(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发注册启动栅栏超时");
        }
        scheduleJobService.registerJob(info());
        return true;
    }

    @Test
    void insertIfAbsentDoesNotReviveLogicallyDeletedJob() {
        Job job = jobFromInfo().init();
        jobMapper.insertIfAbsent(job);

        // 模拟管理端逻辑删除（deleted=1）
        jdbcTemplate.update("UPDATE job SET deleted = 1 WHERE group_name = ? AND name = ?", GROUP, JOB_NAME);

        int inserted = jobMapper.insertIfAbsent(jobFromInfo().init());

        assertEquals(0, inserted, "逻辑删除行仍占用键名，Worker 重启不得复活");
        assertEquals(1, countJobs(), "不应产生新行");
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM job WHERE group_name = ? AND name = ?", Integer.class, GROUP, JOB_NAME);
        assertEquals(1, deleted, "原行仍保持逻辑删除状态");
    }

    @Test
    void insertIfAbsentPropagatesOversizedFieldError() {
        Job job = jobFromInfo().init();
        job.setName("x".repeat(200)); // 超出 VARCHAR(128)

        assertThrows(DataIntegrityViolationException.class, () -> jobMapper.insertIfAbsent(job),
                "字段超长等非唯一键错误必须抛出，不得被当作「已存在」静默吞掉");
        assertEquals(0, countJobs(), "超长插入不应产生数据");
    }

    @Test
    void registerJobFirstTimeWritesRegisterChange() {
        scheduleJobService.registerJob(info());

        assertEquals(1, countJobs());
        assertEquals(1, countRegisterChanges(), "首次注册应写一条 change_type=1 记录");
    }

    @Test
    void registerJobDuplicateWritesNoExtraChange() {
        scheduleJobService.registerJob(info());
        scheduleJobService.registerJob(info());

        assertEquals(1, countJobs(), "重复注册不新增作业行");
        assertEquals(1, countRegisterChanges(), "重复注册不新增变更记录");
    }

    @Test
    void registerJobAfterLogicalDeleteDoesNotRevive() {
        scheduleJobService.registerJob(info());
        jdbcTemplate.update("UPDATE job SET deleted = 1 WHERE group_name = ? AND name = ?", GROUP, JOB_NAME);

        scheduleJobService.registerJob(info());

        assertEquals(1, countJobs(), "逻辑删除后 Worker 重启不得复活作业");
        assertEquals(1, countRegisterChanges(), "复活尝试不得写注册变更");
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM job WHERE group_name = ? AND name = ?", Integer.class, GROUP, JOB_NAME);
        assertEquals(1, deleted);
    }

    @Test
    void registerJobDoesNotTouchInstanceTable() {
        Integer totalBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM instance", Integer.class);
        Integer ownedBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instance WHERE name LIKE ?", Integer.class, PREFIX + "%");

        scheduleJobService.registerJob(info());

        Integer totalAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM instance", Integer.class);
        Integer ownedAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instance WHERE name LIKE ?", Integer.class, PREFIX + "%");
        assertEquals(totalBefore, totalAfter, "作业注册前后整个 instance 表总数必须不变");
        assertEquals(ownedBefore, ownedAfter, "作业注册不得写入本测试前缀实例");
    }

    @Test
    void registerJobRejectsBlankCron() {
        JobInfo bad = info();
        bad.setCron("not-a-cron");

        assertThrows(RuntimeException.class, () -> scheduleJobService.registerJob(bad),
                "非法 cron 应被拒绝");
        assertEquals(0, countJobs(), "非法注册不得落库");
    }

    @Test
    void registerJobRollsBackWhenChangeFeedFails() {
        // 故障注入：变更源写入失败（模拟 DB 抖动 / 下游不可用）。
        // registerJob 的事务边界为「Job 行插入 + changeType=1 变更记录」同事务（ScheduleJobService 72-101），
        // record 抛出的 RuntimeException 未被吞掉，应触发整体回滚。
        Mockito.doThrow(new RuntimeException("change feed down"))
                .when(jobChangeRep).record(any(), any(), any(), any(), any());

        try {
            assertThrows(RuntimeException.class, () -> scheduleJobService.registerJob(info()),
                    "change feed 失败必须向上抛出，不得被吞掉");
            assertEquals(0, countJobs(), "事务应整体回滚，job 表不得残留作业行");
            assertEquals(0, countRegisterChanges(), "事务应整体回滚，job_change 不得残留注册变更");
        } finally {
            Mockito.reset(jobChangeRep);
        }
    }

    private Job jobFromInfo() {
        return com.wly.job.server.convert.JobBeanConverter.convert(info());
    }

    private int countJobs() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job WHERE group_name = ? AND name = ?", Integer.class, GROUP, JOB_NAME);
    }

    private int countRegisterChanges() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_change WHERE job_name = ? AND change_type = 1",
                Integer.class, JOB_NAME);
        return count == null ? 0 : count;
    }
}
