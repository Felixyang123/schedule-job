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

@Slf4j
/**
 * 多 Admin 地址注册器（ADR-0004 决策 #10/#11）：
 * 选择器选主目标，失败按 (start+i)%N 顺序转移；一次成功即完成本次注册/心跳，
 * 全部失败仅告警不抛出（延续现状容错）。
 */
public record DefaultRemoteJobRegistry(List<RestClientHelper> helpers, AdminNodeSelector selector)
        implements RemoteJobRegistry {

    /**
     * 兼容构造：单地址行为与旧版一致。
     */
    public DefaultRemoteJobRegistry(RestClientHelper helper) {
        this(helper == null ? List.of() : List.of(helper), new RoundRobinAdminNodeSelector());
    }

    @Override
    public void register(JobInfo jobInfo, String instanceKey) {
        if (jobInfo == null) {
            return;
        }
        // instanceKey 只作为选择 Admin 节点的路由键，请求体仅为作业元数据
        postWithFailover("/open/job/register", jobInfo, instanceKey);
    }

    @Override
    public void register(JobInstance jobInstance) {
        if (jobInstance == null) {
            return;
        }
        postWithFailover("/open/job/instance/register", jobInstance, jobInstance.getInstanceKey());
    }

    /**
     * 带故障转移的 HTTP 提交：先由选择器定首选 Admin 下标，再按 (start+i)%N 依次尝试，
     * 一次成功即返回；全部失败仅记录告警，不向上抛异常，保证注册/心跳不中断主流程。
     * <p>当前机制覆盖 Worker 注册所需的有限节点选择、同步探活（请求成功即活）与单轮故障转移；
     * 心跳任务会在下个周期自然重试。独立健康探测、熔断和退避属于更完整的客户端治理能力，
     * 需要新增状态机与配置，不能作为本方法的局部修复引入。
     */
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
                    log.error("Register fail: {}, admin: {}, message: {}", path, helper.baseUrl(),
                            result.getMessage());
                } else {
                    log.debug("register ok, path: {}, admin: {}, key: {}", path, helper.baseUrl(), key);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Admin unreachable: {} ({}), try next", helper.baseUrl(), e);
            }
        }
        log.error("All admin addresses unreachable, last error:", lastError);
    }
}
