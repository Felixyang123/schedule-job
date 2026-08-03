# Phase B: Worker SDK 多地址 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Worker（执行器侧）标配多 Admin 地址能力：`serverAddress` 支持逗号分隔多地址，注册/心跳通过 `AdminNodeSelector`（轮询默认/随机/哈希）选主目标并按序故障转移。

**Architecture:** `ScheduleJobConfigProps.serverAddress` 改为 `List<String>`（Spring 宽松绑定，单值/逗号多值兼容）；`ScheduleJobCoreFactory` 为每个地址构建一个 `RestClientHelper`；`DefaultRemoteJobRegistry` 每次注册/心跳用 `AdminNodeSelector` 选起始下标，失败按 `(start+i)%N` 顺序重试，一次成功即结束，全部失败仅告警。保留旧 String 构造重载与单 Helper 构造，保证既有调用方零改动。

**Tech Stack:** Java 21、Spring Boot 3.5.6、spring-web `RestClient`、JUnit 5 + Mockito。

## Global Constraints

- JDK 21，构建命令：`$env:JAVA_HOME='C:\Users\wangyang\.jdks\ms-21.0.10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn test`
- 不引入新运行时依赖（测试依赖 `spring-boot-starter-test` 为 test scope）。
- `serverAddress` 单值配置必须零改动兼容；`serverSelector` 默认 `ROUND_ROBIN`。
- 注册/心跳全部地址失败时不得抛出异常（延续现状容错）。
- 每次 commit 前必须通过 `mvn -q -pl core,job-spring-boot-starter -am test`（或全量 `mvn test`）验证。

---

### Task B1: AdminNodeSelector 抽象与三个实现

**Files:**
- Create: `core/src/main/java/com/wly/job/core/selector/AdminNodeSelector.java`
- Create: `core/src/main/java/com/wly/job/core/selector/RoundRobinAdminNodeSelector.java`
- Create: `core/src/main/java/com/wly/job/core/selector/RandomAdminNodeSelector.java`
- Create: `core/src/main/java/com/wly/job/core/selector/HashAdminNodeSelector.java`
- Create: `core/src/main/java/com/wly/job/core/selector/AdminNodeSelectorFactory.java`
- Modify: `core/pom.xml`（新增 test scope 的 spring-boot-starter-test）
- Test: `core/src/test/java/com/wly/job/core/selector/AdminNodeSelectorTest.java`

**Interfaces:**
- Consumes: 现有 `core/pom.xml`。
- Produces: `AdminNodeSelector.select(int size, String key) -> int`；`RoundRobinAdminNodeSelector` / `RandomAdminNodeSelector` / `HashAdminNodeSelector`；`AdminNodeSelectorFactory.create(String) -> AdminNodeSelector`（Task B3 使用）。

- [ ] **Step 1: 写失败测试**

`core/pom.xml` 增加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

`AdminNodeSelectorTest.java`：

```java
package com.wly.job.core.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminNodeSelectorTest {

    @Test
    void roundRobinCyclesAndWraps() {
        AdminNodeSelector selector = new RoundRobinAdminNodeSelector();
        assertEquals(0, selector.select(3, "k"));
        assertEquals(1, selector.select(3, "k"));
        assertEquals(2, selector.select(3, "k"));
        assertEquals(0, selector.select(3, "k"));
    }

    @Test
    void randomStaysInRange() {
        AdminNodeSelector selector = new RandomAdminNodeSelector();
        for (int i = 0; i < 100; i++) {
            int index = selector.select(3, "k");
            assertTrue(index >= 0 && index < 3);
        }
    }

    @Test
    void hashIsStablePerKey() {
        AdminNodeSelector selector = new HashAdminNodeSelector();
        assertEquals(selector.select(4, "job:host:8101"), selector.select(4, "job:host:8101"));
        assertEquals(selector.select(4, "other:host:8101"), selector.select(4, "other:host:8101"));
    }

    @Test
    void factoryDefaultsToRoundRobin() {
        assertInstanceOf(RoundRobinAdminNodeSelector.class, AdminNodeSelectorFactory.create(null));
        assertInstanceOf(RoundRobinAdminNodeSelector.class, AdminNodeSelectorFactory.create("round_robin"));
        assertInstanceOf(RandomAdminNodeSelector.class, AdminNodeSelectorFactory.create("RANDOM"));
        assertInstanceOf(HashAdminNodeSelector.class, AdminNodeSelectorFactory.create("hash"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl core -am test -Dtest=AdminNodeSelectorTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`AdminNodeSelector.java`：

```java
package com.wly.job.core.selector;

/**
 * Worker 侧 Admin 节点选择器（ADR-0004 决策 #11）：
 * 从地址列表中选择首选下标；故障转移由调用方按 (start+i)%N 顺序执行。
 * 约定：size 必须大于 0。
 */
public interface AdminNodeSelector {

    int select(int size, String key);
}
```

`RoundRobinAdminNodeSelector.java`：

```java
package com.wly.job.core.selector;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内原子游标轮询：每次心跳起点 +1，稳态下各 Admin 节点流量均衡。
 */
public class RoundRobinAdminNodeSelector implements AdminNodeSelector {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public int select(int size, String key) {
        return Math.floorMod(counter.getAndIncrement(), size);
    }
}
```

`RandomAdminNodeSelector.java`：

```java
package com.wly.job.core.selector;

import java.util.concurrent.ThreadLocalRandom;

public class RandomAdminNodeSelector implements AdminNodeSelector {

    @Override
    public int select(int size, String key) {
        return ThreadLocalRandom.current().nextInt(size);
    }
}
```

`HashAdminNodeSelector.java`：

```java
package com.wly.job.core.selector;

/**
 * 按 key（实例键 discoveryKey:host:port）稳定钉住同一个 Admin；故障时由调用方循环转移。
 */
public class HashAdminNodeSelector implements AdminNodeSelector {

    @Override
    public int select(int size, String key) {
        return Math.floorMod(key == null ? 0 : key.hashCode(), size);
    }
}
```

`AdminNodeSelectorFactory.java`：

```java
package com.wly.job.core.selector;

/**
 * 按配置名创建选择器：ROUND_ROBIN（默认）/ RANDOM / HASH。
 */
public final class AdminNodeSelectorFactory {

    private AdminNodeSelectorFactory() {
    }

    public static AdminNodeSelector create(String name) {
        String type = name == null ? "" : name.trim().toUpperCase();
        return switch (type) {
            case "RANDOM" -> new RandomAdminNodeSelector();
            case "HASH" -> new HashAdminNodeSelector();
            default -> new RoundRobinAdminNodeSelector();
        };
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl core -am test -Dtest=AdminNodeSelectorTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/wly/job/core/selector core/src/test/java/com/wly/job/core/selector/AdminNodeSelectorTest.java core/pom.xml
git commit -m "feat: AdminNodeSelector 抽象（轮询/随机/哈希）与工厂"
```

---

### Task B2: ScheduleJobConfigProps 多地址与选择器配置

**Files:**
- Modify: `job-spring-boot-starter/src/main/java/com/wly/job/starter/config/ScheduleJobConfigProps.java`
- Modify: `job-spring-boot-starter/pom.xml`（新增 test scope 的 spring-boot-starter-test）
- Test: `job-spring-boot-starter/src/test/java/com/wly/job/starter/config/ScheduleJobConfigPropsTest.java`

**Interfaces:**
- Consumes: 现有 `ScheduleJobConfigProps`。
- Produces: `ScheduleJobConfigProps.getServerAddress() -> List<String>`、`getServerSelector() -> String`（默认 `ROUND_ROBIN`）；Task B3 使用。

- [ ] **Step 1: 写失败测试**

`job-spring-boot-starter/pom.xml` 增加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

`ScheduleJobConfigPropsTest.java`：

```java
package com.wly.job.starter.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleJobConfigPropsTest {

    @Test
    void defaultsAreRoundRobinAndEmptyAddresses() {
        ScheduleJobConfigProps props = new ScheduleJobConfigProps();
        assertEquals("ROUND_ROBIN", props.getServerSelector());
        assertNotNull(props.getServerAddress());
        assertTrue(props.getServerAddress().isEmpty());
    }

    @Test
    void addressesSettable() {
        ScheduleJobConfigProps props = new ScheduleJobConfigProps();
        props.setServerAddress(List.of("http://a:8100", "http://b:8100"));
        assertEquals(2, props.getServerAddress().size());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl job-spring-boot-starter -am test -Dtest=ScheduleJobConfigPropsTest`
Expected: 编译失败（`serverAddress` 类型不是 List）。

- [ ] **Step 3: 实现**

`ScheduleJobConfigProps.java`：字段替换/追加为：

```java
    /**
     * Admin 地址列表（支持逗号分隔；单值兼容，如 http://a:8100,http://b:8100）
     */
    private List<String> serverAddress = new ArrayList<>();

    /**
     * Admin 节点选择算法: ROUND_ROBIN（默认）/ RANDOM / HASH
     */
    private String serverSelector = "ROUND_ROBIN";
```

新增 import：`java.util.ArrayList`、`java.util.List`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl job-spring-boot-starter -am test -Dtest=ScheduleJobConfigPropsTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: Commit**

```bash
git add job-spring-boot-starter/src/main/java/com/wly/job/starter/config/ScheduleJobConfigProps.java job-spring-boot-starter/pom.xml job-spring-boot-starter/src/test/java/com/wly/job/starter/config/ScheduleJobConfigPropsTest.java
git commit -m "feat: schedule-job.serverAddress 支持多地址、新增 serverSelector 配置"
```

---

### Task B3: 注册/心跳多地址故障转移

**Files:**
- Modify: `core/src/main/java/com/wly/job/core/registry/DefaultRemoteJobRegistry.java`
- Modify: `core/src/main/java/com/wly/job/core/ScheduleJobCoreFactory.java`
- Modify: `job-spring-boot-starter/src/main/java/com/wly/job/starter/config/ScheduleJobAutoConfiguration.java`
- Modify: `samples/job-sample/src/main/resources/application.yml`
- Modify: `samples/register-center-registry-sample/src/main/java/com/wly/job/samples/center/config/CommonConfiguration.java`
- Test: `core/src/test/java/com/wly/job/core/registry/DefaultRemoteJobRegistryTest.java`

**Interfaces:**
- Consumes: Task B1 的 `AdminNodeSelectorFactory`；Task B2 的 `getServerAddress()/getServerSelector()`。
- Produces: `DefaultRemoteJobRegistry(List<RestClientHelper>, AdminNodeSelector)` 主构造 + `DefaultRemoteJobRegistry(RestClientHelper)` 兼容构造；`ScheduleJobCoreFactory` 新增 `List<String> serverAddresses + String serverSelector` 构造重载，保留旧 String 构造。

- [ ] **Step 1: 写失败测试**

`DefaultRemoteJobRegistryTest.java`：

```java
package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.selector.RoundRobinAdminNodeSelector;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DefaultRemoteJobRegistryTest {

    private final RestClientHelper first = mock(RestClientHelper.class);
    private final RestClientHelper second = mock(RestClientHelper.class);

    private JobInstance instance() {
        return JobInstance.builder().discoveryKey("group-a").host("10.0.0.1").port(8101).build();
    }

    @Test
    void failoverToNextHelperOnNetworkError() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        when(second.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));

        verify(second).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void stopsAfterFirstSuccess() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.success());
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        registry.register(instance());

        verify(first).post(anyString(), any(), any(ParameterizedTypeReference.class));
        verify(second, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void allFailuresAreSwallowed() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        when(second.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new ScheduleException("connect fail"));
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));
    }

    @Test
    void businessFailureDoesNotFailover() {
        when(first.post(anyString(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(Result.fail("rejected"));
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(first, second), new RoundRobinAdminNodeSelector());

        registry.register(instance());

        verify(second, never()).post(anyString(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    void emptyAddressListIsNoOp() {
        DefaultRemoteJobRegistry registry =
                new DefaultRemoteJobRegistry(List.of(), new RoundRobinAdminNodeSelector());

        assertDoesNotThrow(() -> registry.register(instance()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl core -am test -Dtest=DefaultRemoteJobRegistryTest`
Expected: 编译失败（构造签名不匹配）。

- [ ] **Step 3: 实现**

`DefaultRemoteJobRegistry.java` 整体替换为：

```java
package com.wly.job.core.registry;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.core.helper.RestClientHelper;
import com.wly.job.core.selector.AdminNodeSelector;
import com.wly.job.core.selector.RoundRobinAdminNodeSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

/**
 * 多 Admin 地址注册器（ADR-0004 决策 #10/#11）：
 * 选择器选主目标，失败按 (start+i)%N 顺序转移；一次成功即完成本次注册/心跳，
 * 全部失败仅告警不抛出（延续现状容错）。
 */
@Slf4j
public record DefaultRemoteJobRegistry(List<RestClientHelper> helpers, AdminNodeSelector selector)
        implements RemoteJobRegistry {

    /**
     * 兼容构造：单地址行为与旧版一致。
     */
    public DefaultRemoteJobRegistry(RestClientHelper helper) {
        this(helper == null ? List.of() : List.of(helper), new RoundRobinAdminNodeSelector());
    }

    @Override
    public void register(JobInfo jobInfo) {
        if (jobInfo == null || jobInfo.getInstance() == null) {
            return;
        }
        postWithFailover("/open/job/register", jobInfo, jobInfo.getInstance().getInstanceKey());
    }

    @Override
    public void register(JobInstance jobInstance) {
        if (jobInstance == null) {
            return;
        }
        postWithFailover("/open/job/instance/register", jobInstance, jobInstance.getInstanceKey());
    }

    private void postWithFailover(String path, Object body, String key) {
        if (helpers == null || helpers.isEmpty()) {
            log.error("No admin address configured, skip register: {}", path);
            return;
        }
        int start = selector.select(helpers.size(), key);
        Exception lastError = null;
        for (int i = 0; i < helpers.size(); i++) {
            RestClientHelper helper = helpers.get((start + i) % helpers.size());
            try {
                Result<Void> result = helper.post(path, body, new ParameterizedTypeReference<>() {
                });
                if (result != null && !result.getSuccess()) {
                    log.error("Register fail: {}, message: {}", path, result.getMessage());
                }
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Admin unreachable: {}, try next", helper, e);
            }
        }
        log.error("All admin addresses unreachable, last error:", lastError);
    }
}
```

`ScheduleJobCoreFactory.java`：

- 保留原 10 参 String 构造并委托；新增 10 参 List 构造：

```java
    /**
     * 兼容构造：单地址、ROUND_ROBIN 选择器。
     */
    public ScheduleJobCoreFactory(RestClientHelper restClientHelper,
                                  InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  String serverAddress,
                                  String accessToken,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(restClientHelper, jobRegistry, remoteJobRegistry, invocationHooks, port,
                serverAddress == null ? List.of() : List.of(serverAddress), accessToken,
                "ROUND_ROBIN", group, enableGroup, heartbeatInterval);
    }

    public ScheduleJobCoreFactory(RestClientHelper restClientHelper,
                                  InnerJobRegistry jobRegistry,
                                  RemoteJobRegistry remoteJobRegistry,
                                  List<InvocationHook> invocationHooks,
                                  int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        List<String> addresses = serverAddresses == null ? List.of() : serverAddresses;
        List<RestClientHelper> helpers = addresses.stream()
                .map(address -> RestClientHelper.builder().bearerToken(accessToken).baseUrl(address).build())
                .toList();
        this.restClientHelper = helpers.isEmpty() ? null : helpers.getFirst();
        this.innerJobRegistry = Optional.ofNullable(jobRegistry).orElse(new DefaultInnerJobRegistry());
        this.remoteJobRegistry = Optional.ofNullable(remoteJobRegistry)
                .orElse(new DefaultRemoteJobRegistry(helpers, AdminNodeSelectorFactory.create(serverSelector)));
        this.invocationHooks = Optional.ofNullable(invocationHooks).orElse(new ArrayList<>());
        this.port = port;
        this.groupName = group;
        this.enableGroup = enableGroup;
        this.heartbeatInterval = heartbeatInterval;

        this.jobBootstrap = new JobBootstrap(port, new JobInstanceHandler(this.innerJobRegistry));
        this.jobBootstrap.start();
    }
```

- 原 6 参 String 构造保留并委托到 List 版本；新增 7 参 List 构造：

```java
    public ScheduleJobCoreFactory(int port,
                                  String serverAddress,
                                  String accessToken,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, null, port,
                serverAddress == null ? List.of() : List.of(serverAddress), accessToken,
                "ROUND_ROBIN", group, enableGroup, heartbeatInterval);
    }

    public ScheduleJobCoreFactory(int port,
                                  List<String> serverAddresses,
                                  String accessToken,
                                  String serverSelector,
                                  String group,
                                  Boolean enableGroup,
                                  long heartbeatInterval) {
        this(null, null, null, null, port, serverAddresses, accessToken, serverSelector,
                group, enableGroup, heartbeatInterval);
    }
```

- 删除旧 10 参构造的函数体（被上面的委托版本取代）；新增 import：`com.wly.job.core.selector.AdminNodeSelectorFactory`。

`ScheduleJobAutoConfiguration.java` 的工厂 Bean 改为：

```java
    @Bean
    @ConditionalOnMissingBean
    public ScheduleJobCoreFactory scheduleJobCoreFactory(ScheduleJobConfigProps props) {
        return new ScheduleJobCoreFactory(
                props.getPort(),
                props.getServerAddress(),
                props.getAccessToken(),
                props.getServerSelector(),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getName).orElse(null),
                Optional.ofNullable(props.getGroup()).map(ScheduleJobConfigProps.Group::getEnabled).orElse(null),
                props.getHeartbeatInterval()
        );
    }
```

`samples/job-sample/src/main/resources/application.yml` 的 `schedule-job:` 段改为：

```yaml
schedule-job:
  # 多 Admin 地址用逗号分隔（如 http://a:8100,http://b:8100）；单地址保持原样
  serverAddress: http://localhost:8100
  # Admin 节点选择算法: ROUND_ROBIN（默认）/ RANDOM / HASH
  serverSelector: ROUND_ROBIN
  accessToken: defaultToken
  port: 8101
  heartbeatInterval: 10
  group:
    name: ${spring.application.name}
    enabled: true
```

`samples/register-center-registry-sample/.../CommonConfiguration.java` 的 `restClientHelper` Bean 改为取首个地址（`serverAddress` 已是 List）：

```java
    @Bean
    public RestClientHelper restClientHelper(ScheduleJobConfigProps props) {
        return RestClientHelper.builder()
                .bearerToken(props.getAccessToken())
                .baseUrl(props.getServerAddress().getFirst())
                .build();
    }
```

（`new DefaultRemoteJobRegistry(restClientHelper)` 与 10 参工厂调用无需改动——兼容构造与重载已保留。）

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl core -am test -Dtest=DefaultRemoteJobRegistryTest,AdminNodeSelectorTest`
Expected: PASS（9 个用例）。

- [ ] **Step 5: 全量回归 + Commit**

Run: `mvn -q test`
Expected: BUILD SUCCESS（含 admin 既有测试与两个 sample 编译）。

```bash
git add core/src/main/java/com/wly/job/core/registry/DefaultRemoteJobRegistry.java core/src/main/java/com/wly/job/core/ScheduleJobCoreFactory.java job-spring-boot-starter/src/main/java/com/wly/job/starter/config/ScheduleJobAutoConfiguration.java samples/job-sample/src/main/resources/application.yml samples/register-center-registry-sample/src/main/java/com/wly/job/samples/center/config/CommonConfiguration.java core/src/test/java/com/wly/job/core/registry/DefaultRemoteJobRegistryTest.java
git commit -m "feat: 注册/心跳多地址故障转移（DefaultRemoteJobRegistry + 工厂重载）"
```

---

## Self-Review

**Spec 覆盖：**
- 决策 #10（多地址）→ Task B2 + B3；#11（AdminNodeSelector 抽象）→ Task B1；#13（server-selector 默认）→ Task B2。
- Spec 阶段 B 验收 1-4 → B2/B3（验收 1）、B3（验收 2/4）、B1（验收 3）、B3 全量回归（验收 5）。

**占位符扫描：** 无 TBD/TODO/"适当处理"类占位；所有代码步骤均给出完整代码。

**类型一致性：**
- `AdminNodeSelector.select(int, String)` 在 B1 定义、B3 调用一致。
- `DefaultRemoteJobRegistry(List<RestClientHelper>, AdminNodeSelector)` 在 B3 测试与实现一致；单 Helper 兼容构造签名与 sample 现有调用一致。
- `ScheduleJobCoreFactory` 的 List 重载与 `ScheduleJobAutoConfiguration` 的调用（`getServerAddress()` 返回 `List<String>`）一致。
- `serverSelector` 枚举名（ROUND_ROBIN/RANDOM/HASH）在 B1 工厂、B2 默认值、sample 注释中一致。
