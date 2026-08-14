package com.wly.job.server.convert;

import com.wly.job.common.bean.JobInfo;
import com.wly.job.common.bean.JobInstance;
import com.wly.job.common.enumeration.JobTypeEnum;
import com.wly.job.common.enumeration.ScheduleStrategyEnum;
import com.wly.job.server.dao.entity.JobGroup;
import com.wly.job.server.dao.entity.Instance;
import com.wly.job.server.dao.entity.Job;
import com.wly.job.server.dao.entity.ScheduleRec;
import com.wly.job.server.enumeration.JobStatusEnum;
import com.wly.job.server.enumeration.ScheduleJobStatusEnum;
import com.wly.job.server.pojo.req.AddGroupReq;
import com.wly.job.server.pojo.req.EditJobReq;
import com.wly.job.server.pojo.resp.GroupResp;
import com.wly.job.server.pojo.resp.JobResp;
import com.wly.job.server.pojo.resp.ScheduleRecResp;

import java.util.Date;

/**
 * 数据模型转换器（静态方法，无状态）。
 *
 * <p>在底层 DTO（JobInfo / JobInstance / 实体类）与视图层对象（JobResp / ScheduleRecResp 等）之间
 * 双向转换；实体 → 视图时补充状态描述文案（statusDesc / typeDesc / strategyDesc）。
 * 保留手写实现，不引入 MapStruct：项目约束不新增运行时依赖（AGENTS.md §5.1）；
 * 枚举描述转换等业务逻辑本就需要显式代码，MapStruct 无法免除。
 */
public class JobBeanConverter {

    /** 执行器注册载荷 JobInfo → 作业实体（含 init 默认值） */
    public static Job convert(JobInfo jobInfo) {
        Job job = Job.builder()
                .groupName(jobInfo.getGroup())
                .name(jobInfo.getJobname())
                .description(jobInfo.getDescription())
                .executeParam(jobInfo.getExecuteParam())
                .cron(jobInfo.getCron())
                .status(Job.ENABLE)
                .type(jobInfo.getType())
                .strategy(jobInfo.getStrategy())
                .build();
        return job.init();
    }

    /** 实例实体 → 调度链路 JobInstance（discoveryKey 取实例名） */
    public static JobInstance convert(Instance instance) {
        return JobInstance.builder()
                .discoveryKey(instance.getName())
                .host(instance.getHost())
                .port(instance.getPort())
                .status(instance.getStatus())
                .expireTime(instance.getExpireTime())
                .build();
    }

    /** 调度链路 JobInstance → 实例实体（持久化用） */
    public static Instance convert(JobInstance jobInstance) {
        return Instance.builder()
                .name(jobInstance.getDiscoveryKey())
                .host(jobInstance.getHost())
                .port(jobInstance.getPort())
                .status(jobInstance.getStatus())
                .expireTime(jobInstance.getExpireTime())
                .build();
    }

    /** 分组实体 → 分组视图 */
    public static GroupResp convert(JobGroup jobGroup) {
        return GroupResp.builder()
                .id(jobGroup.getId())
                .name(jobGroup.getName())
                .description(jobGroup.getDescription())
                .createTime(jobGroup.getCreateTime())
                .creator(jobGroup.getCreator())
                .build();
    }

    /** 新增分组请求 → 分组实体 */
    public static JobGroup convert(AddGroupReq req) {
        return JobGroup.builder()
                .name(req.getName())
                .description(req.getDescription())
                .createTime(new Date())
                .creator("")
                .build();
    }

    /** 作业实体 → 作业视图（补充状态 / 类型 / 策略描述文案） */
    public static JobResp convert(Job job) {
        return JobResp.builder()
                .id(job.getId())
                .groupName(job.getGroupName())
                .name(job.getName())
                .description(job.getDescription())
                .executeParam(job.getExecuteParam())
                .cron(job.getCron())
                .status(job.getStatus())
                .statusDesc(JobStatusEnum.getDescription(job.getStatus()))
                .type(job.getType())
                .typeDesc(JobTypeEnum.getDescription(job.getType()))
                .strategy(job.getStrategy())
                .strategyDesc(ScheduleStrategyEnum.getDescription(job.getStrategy()))
                .finished(job.getFinished())
                .build();
    }

    /** 编辑请求 → 作业实体（仅携带需更新的非空字段） */
    public static Job convert(EditJobReq req) {
        return Job.builder()
                .id(req.getId())
                .description(req.getDescription())
                .executeParam(req.getExecuteParam())
                .cron(req.getCron())
                .status(req.getStatus())
                .type(req.getType())
                .strategy(req.getStrategy())
                .build();
    }

    /** 调度记录实体 → 调度记录视图（补充状态描述文案） */
    public static ScheduleRecResp convert(ScheduleRec scheduleRec) {
        return ScheduleRecResp.builder()
                .id(scheduleRec.getId())
                .jobId(scheduleRec.getJobId())
                .requestId(scheduleRec.getRequestId())
                .executeParam(scheduleRec.getExecuteParam())
                .executeResult(scheduleRec.getExecuteResult())
                .status(scheduleRec.getStatus())
                .statusDesc(ScheduleJobStatusEnum.getDescription(scheduleRec.getStatus()))
                .scheduleTime(scheduleRec.getScheduleTime())
                .completeTime(scheduleRec.getCompleteTime())
                .operator(scheduleRec.getOperator())
                .build();
    }
}
