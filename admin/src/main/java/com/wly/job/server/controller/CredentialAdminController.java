package com.wly.job.server.controller;

import com.wly.job.common.bean.Result;
import com.wly.job.server.credential.CredentialService;
import com.wly.job.server.pojo.req.ActivateCredentialReq;
import com.wly.job.server.pojo.req.CancelCredentialReq;
import com.wly.job.server.pojo.req.PrepareCredentialReq;
import com.wly.job.server.pojo.req.RevokeCredentialReq;
import com.wly.job.server.pojo.resp.ActivateCredentialResp;
import com.wly.job.server.pojo.resp.CancelCredentialResp;
import com.wly.job.server.pojo.resp.PrepareCredentialResp;
import com.wly.job.server.pojo.resp.RevokeCredentialResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 凭证管理接口（Spec 2026-08-14 §4，ADR 决策 #12~#21）。
 *
 * <p>全部要求登录（{@code /admin/**} 由 {@code AdminAuthInterceptor} 兜底），操作人取自
 * {@code UserSessionContext}（请求体不含 applicant）。业务失败由 Service 抛
 * {@link com.wly.job.common.exception.ScheduleException}，经全局异常处理器转为
 * {@code Result.fail(errorCode, message)}。
 *
 * <p>两阶段轮换用法：prepare（返回明文一次）→ 部署 Worker → activate（旧版立即吊销）；
 * 紧急吊销 revoke（Fail-Closed）；取消待激活 cancel。
 */
@RestController
@RequestMapping("/admin/credential")
@RequiredArgsConstructor
public class CredentialAdminController {

    private final CredentialService credentialService;

    /**
     * 创建 / 轮换准备（明文仅此一次返回）。
     */
    @PostMapping("/prepare")
    public Result<PrepareCredentialResp> prepare(@RequestBody PrepareCredentialReq req) {
        return Result.success(credentialService.prepare(
                req.getApplicationName(), req.getEnv(), req.getExpireDays()));
    }

    /**
     * 轮换生效（默认就绪校验；force=true 须填 reason）。
     */
    @PostMapping("/activate")
    public Result<ActivateCredentialResp> activate(@RequestBody ActivateCredentialReq req) {
        return Result.success(credentialService.activate(
                req.getApplicationName(), req.getEnv(), req.isForce(), req.getReason()));
    }

    /**
     * 取消待激活（reason 必填）。
     */
    @PostMapping("/cancel")
    public Result<CancelCredentialResp> cancel(@RequestBody CancelCredentialReq req) {
        return Result.success(credentialService.cancel(
                req.getApplicationName(), req.getEnv(), req.getReason()));
    }

    /**
     * 紧急吊销（reason 必填；吊销后该身份 /open/** 一律 401，恢复走 prepare）。
     */
    @PostMapping("/revoke")
    public Result<RevokeCredentialResp> revoke(@RequestBody RevokeCredentialReq req) {
        return Result.success(credentialService.revoke(
                req.getApplicationName(), req.getEnv(), req.getReason()));
    }
}
