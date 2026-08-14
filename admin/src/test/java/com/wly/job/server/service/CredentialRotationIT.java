package com.wly.job.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.session.UserSession;
import com.wly.job.common.session.UserSessionContext;
import com.wly.job.server.config.OpenApiAuthContext;
import com.wly.job.server.config.OpenApiTokenInterceptor;
import com.wly.job.server.credential.CredentialService;
import com.wly.job.server.dao.rep.CredentialRep;
import com.wly.job.server.pojo.resp.ActivateCredentialResp;
import com.wly.job.server.pojo.resp.PrepareCredentialResp;
import com.wly.job.server.pojo.resp.RevokeCredentialResp;
import com.wly.job.server.schedule.JobScheduler;
import com.wly.job.server.schedule.ScheduleRecCleaner;
import com.wly.job.server.schedule.ScheduleRunRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证轮换集成测试（真实 MySQL job_test 库，@ActiveProfiles("it")）。
 *
 * <p>覆盖单测无法触达的 DB/事务行为（Spec 2026-08-14 §10 验收 4~9）：
 * <ul>
 *   <li>两阶段轮换全链路：prepare 后新旧凭证双版本并行放行，activate 后旧凭证立即失效；</li>
 *   <li>activate 部署就绪校验（在线实例仍用旧版本 → CREDENTIAL_NOT_READY）；</li>
 *   <li>revoke 后 Fail-Closed（/open/** 鉴权全部拒绝）+ 终态版本清空 token_hash/salt；</li>
 *   <li>并发 prepare 只有一个成功（指针 CAS）；</li>
 *   <li>cancel 只取消 PENDING 不影响 ACTIVE。</li>
 * </ul>
 *
 * <p>测试库须预先初始化：见 docs/sql/schema.sql。运行方式：{@code mvn -pl admin test -Dtest=*IT}。
 */
@SpringBootTest
@ActiveProfiles("it")
class CredentialRotationIT {

    @MockitoBean
    private JobScheduler jobScheduler;

    @MockitoBean
    private ScheduleRunRecovery scheduleRunRecovery;

    @MockitoBean
    private ScheduleRecCleaner scheduleRecCleaner;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private CredentialRep credentialRep;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 类级唯一前缀：并发 Maven 进程 / 并行 IT 互不干扰
    private static final String PREFIX = "it-rot-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String APP = PREFIX + "app";
    private static final String ENV = "prod";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM credential_change WHERE application_name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM instance WHERE application_name LIKE ?", PREFIX + "%");
        jdbcTemplate.queryForList(
                "SELECT id FROM credential WHERE application_name LIKE ?", Long.class, PREFIX + "%")
                .forEach(id -> jdbcTemplate.update("DELETE FROM credential_version WHERE credential_id = ?", id));
        jdbcTemplate.update("DELETE FROM credential WHERE application_name LIKE ?", PREFIX + "%");
        UserSessionContext.clear();
    }

    @BeforeEach
    void login() {
        UserSessionContext.setUserSession(UserSession.builder().userId("it-admin").username("it-admin").build());
    }

    @Test
    void rotationFullCycle() {
        // 1. 首次创建：无身份 → 直接 ACTIVE v1，明文返回一次
        PrepareCredentialResp first = credentialService.prepare(APP, ENV, null);
        assertEquals(1, first.version());
        assertTrue(first.plaintext().startsWith("sj_"));
        assertTokenAccepted(first.plaintext(), 1);

        // 2. 轮换准备：有 active → PENDING v2，明文返回一次
        PrepareCredentialResp second = credentialService.prepare(APP, ENV, null);
        assertEquals(2, second.version());
        // 过渡期双版本并行：新旧明文均可通过 /open/** 鉴权
        assertTokenAccepted(first.plaintext(), 1);
        assertTokenAccepted(second.plaintext(), 2);

        // 3. 部署未就绪：在线实例仍用 v1 → activate 被拒
        insertOnlineInstance(1);
        ScheduleException notReady = assertThrows(ScheduleException.class,
                () -> credentialService.activate(APP, ENV, false, null));
        assertEquals(CredentialService.ERR_NOT_READY, notReady.getErrorCode());

        // 4. 部署完成（实例改用 v2）→ activate 成功，旧版立即吊销
        jdbcTemplate.update("UPDATE instance SET credential_version = 2 WHERE application_name = ? AND env = ?",
                APP, ENV);
        ActivateCredentialResp activated = credentialService.activate(APP, ENV, false, null);
        assertEquals(2, activated.activatedVersion());
        assertEquals(1, activated.revokedVersion());
        // 旧凭证立即失效（无宽限期），新凭证生效
        assertTokenRejected(first.plaintext());
        assertTokenAccepted(second.plaintext(), 2);

        // 5. 被吊销版本清空 token_hash/salt（只留脱敏值与审计）
        assertVersionMaterialCleared(1);
        assertVersionMaterialPresent(2);

        // 6. 紧急吊销 → Fail-Closed
        RevokeCredentialResp revoked = credentialService.revoke(APP, ENV, "leaked");
        assertEquals(2, revoked.revokedVersion());
        assertTokenRejected(second.plaintext());
        assertVersionMaterialCleared(2);
        Integer activeVersion = jdbcTemplate.queryForObject(
                "SELECT active_version FROM credential WHERE application_name = ? AND env = ?",
                Integer.class, APP, ENV);
        assertNull(activeVersion, "revoke 后 active 指针必须为空（Fail-Closed）");

        // 7. 恢复路径：无 active 时 prepare 直接建 ACTIVE v3（版本号不复用）
        PrepareCredentialResp recovered = credentialService.prepare(APP, ENV, null);
        assertEquals(3, recovered.version());
        assertTokenAccepted(recovered.plaintext(), 3);
    }

    @Test
    void concurrentPrepareOnlyOneSucceeds() throws Exception {
        // 前置：顺序建身份 + ACTIVE v1（避免并发身份创建的条件插入 gap 锁死锁，
        // 并发 prepare 的正确性核心在版本插入 + 指针 CAS 的竞争）
        credentialService.prepare(APP, ENV, null);
        assertEquals(1, countVersionRows());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> concurrentPrepare(ready, start)),
                    executor.submit(() -> concurrentPrepare(ready, start)));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个并发线程应就绪");
            start.countDown();
            for (Future<Boolean> result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS), "并发 prepare 调用均应正常完成");
            }
            // 指针 CAS：恰好一个成功（PENDING v2），另一个冲突；版本行 = ACTIVE v1 + PENDING v2
            assertEquals(2, countVersionRows(), "并发 prepare 只能产生一个 pending 版本行");
            // 变更记录 = 前置 1 条 + 并发成功 1 条（失败方事务回滚不落库）
            assertEquals(2, countPrepareChanges(), "并发 prepare 只能新增一条变更记录");
            Integer pending = jdbcTemplate.queryForObject(
                    "SELECT pending_version FROM credential WHERE application_name = ? AND env = ?",
                    Integer.class, APP, ENV);
            assertEquals(2, pending, "成功方应持有 PENDING v2");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发线程池应及时退出");
        }
    }

    @Test
    void cancelOnlyAffectsPending() {
        PrepareCredentialResp first = credentialService.prepare(APP, ENV, null);
        PrepareCredentialResp second = credentialService.prepare(APP, ENV, null);
        assertEquals(2, second.version());
        assertTokenAccepted(second.plaintext(), 2); // pending 明文过渡期可用

        var canceled = credentialService.cancel(APP, ENV, "rollback");
        assertEquals(2, canceled.canceledVersion());

        // ACTIVE v1 不受影响，仍可校验；pending 已取消 → 明文2 不再放行
        assertTokenAccepted(first.plaintext(), 1);
        assertTokenRejected(second.plaintext());
        assertVersionMaterialCleared(2);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT pending_version FROM credential WHERE application_name = ? AND env = ?",
                Integer.class, APP, ENV);
        assertNull(pending, "cancel 后 pending 指针必须清空");
    }

    // ---------------- helpers ----------------

    private boolean concurrentPrepare(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发 prepare 启动栅栏超时");
        }
        try {
            credentialService.prepare(APP, ENV, null);
        } catch (ScheduleException e) {
            // CAS 冲突（唯一成功者之外的并发调用）为预期
            assertTrue(CredentialService.ERR_PENDING_EXISTS.equals(e.getErrorCode())
                    || CredentialService.ERR_VERSION_MISMATCH.equals(e.getErrorCode()), e.getErrorMsg());
        }
        return true;
    }

    // ---------------- helpers ----------------

    private void insertOnlineInstance(int credentialVersion) {
        jdbcTemplate.update("""
                INSERT INTO instance(`name`, `host`, `port`, `application_name`, `env`, `credential_version`,
                                     `status`, `expire_time`, `create_time`, `update_time`)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, NOW(), NOW())
                """, PREFIX + "node", "10.0.0.9", 8101, APP, ENV, credentialVersion,
                new Date(System.currentTimeMillis() + 60_000));
    }

    /** 构造真实 /open/** 鉴权校验：明文 token 应放行且记录到版本号 */
    private void assertTokenAccepted(String token, int expectedVersion) {
        OpenApiTokenInterceptor interceptor = new OpenApiTokenInterceptor(credentialService, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Job-Group", APP);
        request.addHeader("X-Job-Env", ENV);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            boolean passed = interceptor.preHandle(request, response, null);
            assertTrue(passed, "明文凭证应通过鉴权: " + token);
            OpenApiAuthContext auth = (OpenApiAuthContext) request.getAttribute(OpenApiAuthContext.REQUEST_ATTRIBUTE);
            assertNotNull(auth, "鉴权通过应写入身份上下文");
            assertEquals(expectedVersion, auth.credentialVersion());
        } catch (Exception e) {
            throw new AssertionError("鉴权调用异常", e);
        }
    }

    /** 明文 token 应被拒绝（401） */
    private void assertTokenRejected(String token) {
        OpenApiTokenInterceptor interceptor = new OpenApiTokenInterceptor(credentialService, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Job-Group", APP);
        request.addHeader("X-Job-Env", ENV);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            boolean passed = interceptor.preHandle(request, response, null);
            assertTrue(!passed, "失效明文凭证应被拒绝: " + token);
            assertEquals(401, response.getStatus());
        } catch (Exception e) {
            throw new AssertionError("鉴权调用异常", e);
        }
    }

    private void assertVersionMaterialCleared(int version) {
        String hash = jdbcTemplate.queryForObject("""
                SELECT token_hash FROM credential_version v
                JOIN credential c ON v.credential_id = c.id
                WHERE c.application_name = ? AND c.env = ? AND v.version = ?
                """, String.class, APP, ENV, version);
        assertNull(hash, "终态版本（REVOKED/CANCELED）必须清空 token_hash");
    }

    private void assertVersionMaterialPresent(int version) {
        String hash = jdbcTemplate.queryForObject("""
                SELECT token_hash FROM credential_version v
                JOIN credential c ON v.credential_id = c.id
                WHERE c.application_name = ? AND c.env = ? AND v.version = ?
                """, String.class, APP, ENV, version);
        assertNotNull(hash, "ACTIVE 版本必须保留 token_hash");
    }

    private int countVersionRows() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM credential_version v
                JOIN credential c ON v.credential_id = c.id
                WHERE c.application_name = ? AND c.env = ?
                """, Integer.class, APP, ENV);
        return count == null ? 0 : count;
    }

    private int countPrepareChanges() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credential_change WHERE application_name = ? AND env = ? AND change_type = 1",
                Integer.class, APP, ENV);
        return count == null ? 0 : count;
    }
}
