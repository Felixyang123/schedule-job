package com.wly.job.server.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 作业变更源（job_change）变更类型枚举。
 *
 * <p>对应 ADR-0005 变更源模型：
 * <ul>
 *   <li>{@link #REGISTER} 注册（1）、{@link #EDIT} 编辑（2）、{@link #SWITCH} 启停（3）、
 *       {@link #FINISHED} 单次完成（4）、{@link #DELETE} 删除（5）：写路径埋点，须与 Job 行写入同事务。</li>
 *   <li>{@link #REQUEUE} 失败重试（6）：由回调失败 / 超时 / 连接断开 / 常驻清扫等释放 in-flight 的
 *       路径独立插入，供主节点消费后重新入队。</li>
 * </ul>
 * <p>注意：消费端无视 change_type，仅以 jobId 回查当前行 diff，天然幂等。
 */
@Getter
@AllArgsConstructor
public enum JobChangeTypeEnum {
    /** 注册（1） */
    REGISTER(1),
    /** 编辑（2） */
    EDIT(2),
    /** 启停（3） */
    SWITCH(3),
    /** 单次完成（4） */
    FINISHED(4),
    /** 删除（5） */
    DELETE(5),
    /** 失败重试（6，REQUEUE，独立插入） */
    REQUEUE(6);

    private final int code;
}
