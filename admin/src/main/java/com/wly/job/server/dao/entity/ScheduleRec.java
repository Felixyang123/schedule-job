package com.wly.job.server.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 调度执行记录实体，对应 {@code schedule_rec} 表（ScheduleRecord / 调度记录）。
 *
 * <p>记录每一次调度派发到执行器执行的全链路结果，是 At-Least-Once 语义下的审计与对账载体。
 * 状态机：{@link #RUNNING}（执行中，唯一非终态）→ 成功回调置 {@link #SUCCESS}，
 * 失败回调 / 超时 / 连接断开 / 常驻清扫置 {@link #FAIL}。
 *
 * <p>关键约定：
 * <ul>
 *   <li>{@code RUNNING} 是唯一非终态，超过请求超时宽限后由主节点常驻清扫（ScheduleRunRecovery）置 FAIL。</li>
 *   <li>写入走 {@code ScheduleRecQueue} 异步攒批落库（saveBatch），保障日志落库不阻塞主调度循环。</li>
 *   <li>{@code requestId} 与 {@code executionId} 为调度链路追踪 ID。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "schedule_rec", autoResultMap = true)
public class ScheduleRec {
    /** 失败（终态） */
    public static final Integer FAIL = -1;
    /** 执行中 / RUNNING（非终态） */
    public static final Integer RUNNING = 0;
    /** 成功（终态） */
    public static final Integer SUCCESS = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属作业 ID */
    private Long jobId;

    /** 调度链路追踪请求 ID */
    private String requestId;

    /** 本次调度下发的执行参数 */
    private String executeParam;

    /** 执行器返回的执行结果（JSON 字符串） */
    private String executeResult;

    /** 执行状态（-1 失败 / 0 执行中 / 1 成功） */
    private Integer status;

    /** 调度时间 */
    private Date scheduleTime;

    /** 完成时间（终态落库时间） */
    private Date completeTime;

    /** 操作人（触发本次调度的主体） */
    private String operator;
}
