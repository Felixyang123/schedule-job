package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Objects;

/**
 * 作业元数据实体，对应 {@code job} 表。
 *
 * <p>作业是调度框架的核心管理对象，分为两类：
 * <ul>
 *   <li>普通任务（type=0，Normal Job）：按 cron 周期性调度，无业务终态。</li>
 *   <li>单次任务（type=1，Single-Run Job）：调度一次后进入 {@code finished} 业务终态，
 *       成功回调置 finished=1 时带 type=SINGLE 守卫，维护不变量 {@code finished=1 ⇒ type=1}。</li>
 * </ul>
 * <p>关键字段语义：{@code status} 为管理态（0 停止 / 1 运行），与单次任务的 {@code finished} 终态解耦；
 * {@code deleted} 为逻辑删除标记（0 正常 / 1 删除），删除语义为"不打断在途执行、Worker 重新注册不复活任务"；
 * {@code group:name} 构成唯一键，逻辑删除旧行占用键名以复用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "job", autoResultMap = true)
public class Job {

    public static final Integer UNABLE = 0;
    public static final Integer ENABLE = 1;

    private static final String DEFAULT_CREATOR = "system";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 作业分组名，与 {@code name} 组成 {@code uk_group_name_name} 唯一键
     */
    private String groupName;

    /**
     * 作业唯一标识名
     */
    private String name;

    /**
     * 作业描述
     */
    private String description;

    /**
     * 执行参数，随调度请求下发给执行器
     */
    private String executeParam;

    /**
     * Cron 调度表达式
     */
    private String cron;

    /**
     * 状态
     * 0: 停止
     * 1: 运行
     */
    private Integer status;

    /**
     * 任务类型
     * 0: 普通任务
     * 1: 单次任务
     *
     * @see com.wly.job.common.enumeration.JobTypeEnum
     */
    private Integer type;

    /**
     * 调度策略
     *
     * @see com.wly.job.common.enumeration.ScheduleStrategyEnum
     */
    private Integer strategy;

    /**
     * 单次任务完成标记
     * 0: 未完成 1: 已完成（终态，与管理态 status 解耦）
     */
    private Integer finished;

    /**
     * 逻辑删除标记（0 正常 / 1 删除）
     */
    private Integer deleted;

    private Date createTime;

    private Date updateTime;

    /**
     * 创建人。默认值只在注册路径由 {@link #init()} 赋值，<b>不可用 {@code @Builder.Default}</b>：
     * 编辑路径（JobBeanConverter.convert(EditJobReq)）构造的是部分字段 Job，MyBatis-Plus 默认
     * NOT_NULL 更新策略会把非 null 字段写入 SET，届时会静默覆盖原始创建人。
     */
    private String creator;

    private String updater;

    /**
     * 初始化新建作业的默认字段：置为运行中、未完成、未删除，初始化时间戳与审计人。
     * 由注册路径（ScheduleJobService.registerJob）在入库前调用；编辑路径不得调用，
     * 否则会覆盖 creator（见 {@link #creator} 说明）。
     *
     * @return 当前作业对象（支持链式调用）
     */
    public Job init() {
        this.createTime = new Date();
        this.updateTime = this.createTime;
        this.deleted = 0;
        this.status = ENABLE;
        this.finished = 0;
        this.creator = DEFAULT_CREATOR;
        this.updater = DEFAULT_CREATOR;
        return this;
    }

    /**
     * @return 作业是否处于运行状态（管理态 status=1）
     */
    public boolean isEnable() {
        return Objects.equals(this.status, ENABLE);
    }
}
