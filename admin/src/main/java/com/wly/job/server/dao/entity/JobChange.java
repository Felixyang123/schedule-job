package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 作业变更源记录实体，对应 {@code job_change} 表（Change Feed，ADR-0005）。
 *
 * <p>所有作业元数据写路径（注册 / 编辑 / 启停 / 单次完成 / 删除 / 失败重试）在与 Job 行写入
 * 相同事务（REQUEUE 为独立插入）内追加一条记录，构成持久化的变更消息流。主节点按 id 水印
 * 增量消费做对账。
 *
 * <p>关键约定：
 * <ul>
 *   <li>{@code id} 为单调递增水印，消费端以 {@code SELECT id > watermark ORDER BY id} 拉取。</li>
 *   <li>{@code changeType} 见 {@link com.wly.job.server.enumeration.JobChangeTypeEnum}
 *       （1 注册 / 2 编辑 / 3 启停 / 4 单次完成 / 5 删除 / 6 失败重试）；消费端无视该值，
 *       仅以 jobId 回查当前行 diff，天然幂等。</li>
 * </ul>
 */
@Data
@TableName(value = "job_change")
public class JobChange {

    /** 变更记录自增 ID，兼作消费水印 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发生变更的作业 ID */
    private Long jobId;

    /** 变更类型（1 注册 / 2 编辑 / 3 启停 / 4 单次完成 / 5 删除 / 6 失败重试） */
    private Integer changeType;

    /** 变更操作人（写路径为系统时记 "system"） */
    private String operator;

    /** 关联的调度请求 ID，用于链路追踪 */
    private String requestId;

    /** 变更时的作业名快照，便于排查 */
    private String jobName;

    /** 变更时间 */
    private Date createTime;
}
