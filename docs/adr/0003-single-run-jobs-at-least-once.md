# 0003-single-run-jobs-at-least-once

Status: accepted

单次任务（Single-Run Job）的执行契约明确为**至少一次（At-Least-Once）**：Admin 重启或响应丢失时可能重发，因此执行方（Worker）必须幂等。执行失败不进入终态——移除 in-flight 标记后，由定时扫描按 Cron 自然重试；执行成功后任务流转到独立于管理态的终态 **Finished**（Job 新增 `finished` 列，与 `status`（ENABLE/UNABLE）解耦），Finished 的单次任务不再进入调度队列，但管理端仍可手动补跑（不改变终态）。选择 At-Least-Once 而非"最多一次/恰好一次"，是因为后两者需要持久化派发状态或分布式锁/幂等表，与轻量定位不符；失败留痕由 ScheduleRec 承担，`Job.status` 只表示管理态、不承载业务执行状态。本 ADR 澄清并取代 ADR-0001 中"At-Least-Once / Exactly-Once 执行成功"的模糊表述。
