package com.wly.job.server.controller.open;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.bean.Result;
import com.wly.job.server.config.OpenApiAuthContext;
import com.wly.job.server.service.ScheduleJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 开放接口（作业注册、实例心跳，ADR-0006 身份鉴权）。
 *
 * <p>鉴权由 {@link com.wly.job.server.config.OpenApiTokenInterceptor} 完成：Header
 * {@code X-Job-Group}/{@code X-Job-Env} 声明身份、Bearer 携带明文凭证，命中后把
 * {@link OpenApiAuthContext} 写入 request attribute。<b>本 Controller 二次校验</b>
 * Header 身份与 Body 中的 applicationName/env 一致（Spec §3.1），防止持合法凭证
 * 注册到其他身份。
 */
@RestController
@RequestMapping("/open/job")
@RequiredArgsConstructor
public class OpenJobController {
    private final ScheduleJobService scheduleJobService;

    /**
     * 注册任务元数据（作业注册与实例注册解耦，Spec 2026-08-12）。
     * <p>作业请求体 JobInfo 不携带身份字段：身份只从 Header 来，此处校验
     * Header 身份有效（拦截器已保证），不额外比对 Body。
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody JobInfo jobInfo) {
        scheduleJobService.registerJob(jobInfo);
        return Result.success();
    }

    /**
     * 注册 / 刷新执行器实例心跳。
     * <p>二次校验：Body 中的 applicationName/env 必须与 Header 鉴权身份一致；
     * {@code credentialVersion} 由服务端按鉴权结果写入（不信任 Worker 自报，ADR 决策 #16）。
     */
    @PostMapping("/instance/register")
    public Result<Void> registerInstance(@RequestBody JobInstance instance, HttpServletRequest request) {
        OpenApiAuthContext auth = (OpenApiAuthContext) request.getAttribute(OpenApiAuthContext.REQUEST_ATTRIBUTE);
        if (auth == null) {
            return Result.fail("unauthorized", "missing auth context");
        }
        if (!Objects.equals(auth.applicationName(), instance.getApplicationName())
                || !Objects.equals(auth.env(), instance.getEnv())) {
            return Result.fail("CREDENTIAL_IDENTITY_MISMATCH", "unauthorized");
        }
        // 服务端按鉴权结果写入版本（覆盖 Worker 上报值）
        instance.setCredentialVersion(auth.credentialVersion());
        scheduleJobService.registerInstance(instance);
        return Result.success();
    }
}
