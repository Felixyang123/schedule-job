package com.wly.job.server.schedule;

import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.ScheduleJobRequest;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.exception.ScheduleException;
import com.wly.job.common.security.HmacSha256Signer;
import com.wly.job.server.client.ScheduleJobClient;
import com.wly.job.server.client.lb.LoadBalancer;
import com.wly.job.server.credential.CredentialInfo;
import com.wly.job.server.credential.CredentialService;
import com.wly.job.server.registry.Registry;
import com.wly.job.server.dao.entity.Job;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 调度服务实现：固定"发现候选执行器 -> 负载均衡选一台 -> 封包 RPC 发送"的执行链路，
 * 将"以什么为 discoveryKey 发现实例"这一差异点收敛为注入的 {@code discoveryKeyExtractor}
 * 函数（DEFAULT 模式取作业名，GROUP 模式取分组名）。
 *
 * <p>RPC 派发鉴权（ADR-0006 §3.2）：请求<b>不携带可复用凭证</b>，以选中实例的
 * 凭证身份（applicationName + env）查 active 版本摘要（token_hash）为 HMAC-SHA256 密钥，
 * 对规范化请求签名；签名参数（credentialVersion / salt / iterations / timestamp / signature）
 * 随请求携带，nonce 复用 requestId。凭证缺失 / 已过期时拒绝派发（抛业务异常由上层按
 * 失败重试路径处理）。
 */
@Slf4j
public class ScheduleServiceTemplate implements ScheduleService {
    private final ScheduleJobClient client;

    private final LoadBalancer loadBalancer;

    private final Registry registry;

    /** 凭证查询服务（按实例身份取 active 版本摘要签名） */
    private final CredentialService credentialService;

    /**
     * 实例发现键提取函数：决定从注册中心发现候选执行器的维度（作业名 / 分组名）。
     */
    private final Function<Job, String> discoveryKeyExtractor;

    public ScheduleServiceTemplate(ScheduleJobClient client, LoadBalancer loadBalancer, Registry registry,
                                   CredentialService credentialService,
                                   Function<Job, String> discoveryKeyExtractor) {
        this.client = client;
        this.loadBalancer = loadBalancer;
        this.registry = registry;
        this.credentialService = credentialService;
        this.discoveryKeyExtractor = discoveryKeyExtractor;
    }

    /**
     * {@inheritDoc}
     * <p>{@code requestId} 由上层 {@code ScheduleJobService} 在创建 RUNNING 调度记录时确定，
     * 显式传入确保 RPC 请求与 schedule_rec 使用同一 ID；MDC 仅用于日志上下文，不作为业务数据源。
     */
    @Override
    public void schedule(String requestId, Job job) {
        log.debug("Schedule job: {}", job);
        ScheduleContext scheduleContext = new ScheduleContext(discoveryKeyExtractor.apply(job), job.getStrategy());
        List<JobInstance> instances = registry.discover(scheduleContext.getDiscoveryKey());
        log.debug("job: {} -> candidates={}", job.getName(), instances.size());

        // 按作业配置的路由策略选择单台执行器；候选为空则抛出业务异常由上层记录失败重试
        JobInstance instance = loadBalancer.choose(instances, scheduleContext.getStrategy());

        if (instance == null) {
            log.warn("No available schedule instance found, job: {}", job.getName());
            throw new ScheduleException("No available schedule instance found, job: " + job.getName());
        }

        ScheduleJobRequest scheduleJobRequest = buildSignedRequest(requestId, job, instance);

        client.send(scheduleJobRequest, instance, job.getId(), isSingleRun(job));
    }

    /**
     * 构造带签名的派发请求（ADR-0006 §3.2）。
     *
     * <p>签名密钥 = 选中实例凭证身份的 active 版本摘要（token_hash，PBKDF2 派生值）；
     * 规范化串 = {@code requestId | jobname | executeParam | timestamp | nonce(=requestId)}。
     * 凭证缺失 / 身份缺失 / 无 active 版本 / 已过期时抛 {@link ScheduleException}，
     * 由上层失败重试路径（REQUEUE）兜底。
     */
    private ScheduleJobRequest buildSignedRequest(String requestId, Job job, JobInstance instance) {
        if (!StringUtils.hasText(instance.getApplicationName()) || !StringUtils.hasText(instance.getEnv())) {
            throw new ScheduleException("Schedule instance has no credential identity, job: " + job.getName()
                    + ", instance: " + instance.getInstanceKey());
        }
        CredentialInfo info = credentialService.lookup(instance.getApplicationName(), instance.getEnv())
                .orElse(null);
        CredentialInfo.VersionInfo active = info == null ? null : info.active();
        if (active == null) {
            throw new ScheduleException("No active credential for identity: "
                    + instance.getApplicationName() + ":" + instance.getEnv() + ", job: " + job.getName());
        }
        if (active.expireTime() != null && new java.util.Date().after(active.expireTime())) {
            throw new ScheduleException("Active credential expired for identity: "
                    + instance.getApplicationName() + ":" + instance.getEnv() + ", job: " + job.getName());
        }

        long timestamp = System.currentTimeMillis();
        String canonical = HmacSha256Signer.canonical(
                requestId, job.getName(), job.getExecuteParam(), timestamp, requestId);

        return ScheduleJobRequest.builder()
                .traceId(MDC.get("traceId"))
                .requestId(requestId)
                .jobname(job.getName())
                .executeParam(job.getExecuteParam())
                .credentialVersion(active.version())
                .salt(active.salt())
                .iterations(active.iterations())
                .timestamp(timestamp)
                .signature(HmacSha256Signer.sign(active.tokenHash(), canonical))
                .build();
    }

    private boolean isSingleRun(Job job) {
        return job.getType() != null && job.getType() == JobTypeEnum.SINGLE.getCode();
    }
}
