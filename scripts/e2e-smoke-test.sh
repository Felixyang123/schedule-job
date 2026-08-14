#!/usr/bin/env bash
# =====================================================================
# 端到端冒烟测试（E2E Smoke Test）：Admin + Worker 真实进程 + 真实 MySQL
#
# 覆盖 Spec 2026-08-12 §8 中依赖真实部署才能验证的验收项：
#   1. Worker 启动并完成实例/作业注册与心跳（以可观测结果为准）
#   2. GROUP 模式下同组作业的实例注册去重结果
#   3. 调度触发 → Worker 执行 → schedule_rec 成功记录（At-Least-Once 闭环）
#   4. Worker 重启后重复注册幂等：job 行、change_type=1 记录、instance 均不新增
#   5. 逻辑删除作业不被 Worker 重启复活
#
# 前置条件：
#   - 本机 MySQL 8（默认 root/lifan1994，可用 MYSQL_USER/MYSQL_PWD 覆盖）
#   - job_test 库已初始化（运行 docs/sql/schema.sql，1061 重复索引报错可忽略）
#   - JDK 21（JAVA_HOME 或默认 PATH）
#
# 用法：bash scripts/e2e-smoke-test.sh
# 退出码：0 全部通过；非 0 失败（关键检查立即退出，普通检查汇总后退出）。
# =====================================================================
set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADMIN_JAR="${ROOT_DIR}/admin/target/admin-0.0.1-SNAPSHOT.jar"
SAMPLE_JAR="${ROOT_DIR}/samples/job-sample/target/job-sample-0.0.1-SNAPSHOT.jar"
LOG_DIR="${ROOT_DIR}/logs"
ADMIN_LOG="${LOG_DIR}/e2e-admin.log"
SAMPLE_LOG="${LOG_DIR}/e2e-sample.log"
ADMIN_ERR_LOG="${LOG_DIR}/e2e-admin-error.log"
SAMPLE_ERR_LOG="${LOG_DIR}/e2e-sample-error.log"
RUN_ID="${BASHPID}-$(date +%s)-${RANDOM}"
RUN_TOKEN="job-e2e-${RUN_ID}"
ADMIN_PID_FILE="${LOG_DIR}/.e2e-admin-${RUN_ID}.pid"
SAMPLE_PID_FILE="${LOG_DIR}/.e2e-sample-${RUN_ID}.pid"
LOCK_DIR="${LOG_DIR}/.e2e-smoke.lock"
LOCK_HELD=false
DATA_CLEANUP_ARMED=false

ADMIN_PORT="${ADMIN_PORT:-8100}"
SAMPLE_PORT="${SAMPLE_PORT:-8102}"

# 端口号合法性校验：必须为 1~65535 的数字。
for port_var in ADMIN_PORT SAMPLE_PORT; do
    port_value="${!port_var}"
    if ! [[ "${port_value}" =~ ^[1-9][0-9]{0,4}$ ]] || [ "${port_value}" -gt 65535 ]; then
        echo "错误：${port_var}=${port_value} 无效，必须为 1~65535 的端口号。" >&2
        exit 1
    fi
done

DB_NAME="${DB_NAME:-job_test}"
if [ "${DB_NAME}" != "job_test" ]; then
    echo "错误：E2E 脚本仅允许使用 DB_NAME=job_test，拒绝操作 ${DB_NAME}" >&2
    exit 1
fi
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PWD:-lifan1994}"

# Java 命令（优先 JAVA_HOME，否则取 PATH）
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_CMD="${JAVA_HOME}/bin/java"
else
    JAVA_CMD="java"
fi

# MySQL 连接（统一封装）
mysql_job_test() {
    MYSQL_PWD="${DB_PASS}" mysql -h127.0.0.1 -u"${DB_USER}" "${DB_NAME}" "$@"
}

PASS=0
FAIL=0
ADMIN_PID=""
SAMPLE_PID=""
JOB_IDS=""
INSTANCE_NAME=""
INSTANCE_HOST=""
INSTANCE_PORT=""
step() { echo ""; echo "==> $1"; }
ok()   { echo "    PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "    FAIL: $1"; FAIL=$((FAIL+1)); }

check() { # $1=描述，其余为命令及其参数（返回码 0 即通过）
    local desc="$1"
    shift
    if "$@" >/dev/null 2>&1; then ok "${desc}"; else bad "${desc}"; fi
}
fatal_check() { # 关键检查失败立即退出
    local desc="$1"
    shift
    if "$@" >/dev/null 2>&1; then ok "${desc}"; else bad "${desc}"; exit 1; fi
}

curl_test() { # $1=期望 HTTP 状态码，其余为 curl 参数；实际状态码相等即通过
    local expected="$1"
    shift
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "$@")
    [ "${code}" = "${expected}" ]
}

listening_pids() {
    local port="$1"
    netstat -ano 2>/dev/null | awk -v port="${port}" '$2 ~ (":" port "$") && /LISTENING/ && !seen[$NF]++ {print $NF}'
}

port_owned() {
    local port="$1"
    local expected_pid="$2"
    [ -n "${expected_pid}" ] && [ "$(listening_pids "${port}")" = "${expected_pid}" ]
}

# =====================================================================
# 检查谓词（bash 函数，返回码即通过与否；供 check / fatal_check 调用）
# =====================================================================
admin_healthy() {
    curl -s "http://localhost:${ADMIN_PORT}/actuator/health" | grep -q '"status":"UP"'
}

worker_started() {
    grep -q "Started JobSampleApplication" "${SAMPLE_LOG}" 2>/dev/null \
        && port_owned "${SAMPLE_PORT}" "${SAMPLE_PID}" \
        && port_owned 8101 "${SAMPLE_PID}"
}

ports_free() {
    local port
    for port in "$@"; do
        if [ -n "$(listening_pids "${port}")" ]; then
            return 1
        fi
    done
    return 0
}

sql_ok() {
    mysql_job_test -e "$1"
}

count_eq() {
    local expected="$1"
    local sql="$2"
    [ "$(mysql_job_test -N -e "${sql}")" = "${expected}" ]
}

count_gt() {
    local threshold="$1"
    local sql="$2"
    [ "$(mysql_job_test -N -e "${sql}")" -gt "${threshold}" ]
}

jobs_registered() {
    count_eq 4 "SELECT COUNT(*) FROM job WHERE name LIKE 'DemoJob%'"
}

changes_registered() {
    count_eq 4 "SELECT COUNT(*) FROM job_change WHERE change_type=1 AND job_id IN (${JOB_IDS})"
}

instance_row() {
    local name="$1"
    local host="$2"
    local port="$3"
    count_eq 1 "SELECT COUNT(*) FROM instance WHERE name='${name}' AND host='${host}' AND port=${port}"
}

job_deleted_row() {
    local name="$1"
    count_eq 1 "SELECT COUNT(*) FROM job WHERE name='${name}' AND deleted=1 AND id IN (${JOB_IDS})"
}

db_data_absent() {
    [ "$(mysql_job_test -N -e "SELECT COUNT(*) FROM job WHERE name LIKE 'DemoJob%'")" = "0" ] \
        && [ "$(mysql_job_test -N -e "SELECT COUNT(*) FROM job_change WHERE job_name LIKE 'DemoJob%'")" = "0" ] \
        && [ "$(mysql_job_test -N -e "SELECT COUNT(*) FROM instance WHERE name LIKE 'job-sample%'")" = "0" ] \
        && [ "$(mysql_job_test -N -e "SELECT COUNT(*) FROM credential WHERE application_name='e2e-rot-app'")" = "0" ] \
        && [ "$(mysql_job_test -N -e "SELECT COUNT(*) FROM job WHERE name='e2e-rot-job'")" = "0" ]
}

# 变量格式校验（保持原有校验强度，防止未经格式校验的值拼入 SQL）
job_ids_valid_4() {
    [[ "${JOB_IDS}" =~ ^[0-9]+(,[0-9]+){3}$ ]]
}

instance_key_valid() {
    [ "${INSTANCE_NAME}" = "job-sample-server" ] \
        && [[ "${INSTANCE_HOST}" =~ ^[A-Za-z0-9:.%-]+$ ]] \
        && [ "${INSTANCE_PORT}" = "8101" ]
}

windows_path() {
    cygpath -w "$1"
}

# 用 Start-Process 获取真实 Windows Java PID，避免 Git Bash 后台 PID 与监听进程 PID 不一致。
start_java() {
    local role="$1"
    local pid_file="$2"
    local stdout_log="$3"
    local stderr_log="$4"
    shift 4

    local java_executable="${JAVA_CMD}"
    if [[ "${java_executable}" == */* ]]; then
        java_executable=$(windows_path "${java_executable}")
    fi

    local pid_file_win stdout_log_win stderr_log_win
    pid_file_win=$(windows_path "${pid_file}")
    stdout_log_win=$(windows_path "${stdout_log}")
    stderr_log_win=$(windows_path "${stderr_log}")
    rm -f "${pid_file}"

    # 参数通过环境变量传入，避免 Git Bash 将 JDBC URL 中的 & 拼入 PowerShell -Command 后重新解析。
    E2E_JAVA_EXE="${java_executable}" \
    E2E_STDOUT_LOG="${stdout_log_win}" \
    E2E_STDERR_LOG="${stderr_log_win}" \
    E2E_PID_FILE="${pid_file_win}" \
    E2E_RUN_TOKEN="${RUN_TOKEN}:${role}" \
    E2E_JAVA_ARGS="$(printf '%s\n' "$@")" \
    powershell.exe -NoProfile -NonInteractive -Command \
        '& { $processArgs = @($env:E2E_JAVA_ARGS -split "`n" | Where-Object { $_ -ne "" }); $processArgs += "-Djob.e2e.token=$($env:E2E_RUN_TOKEN)"; $process = Start-Process -FilePath $env:E2E_JAVA_EXE -ArgumentList $processArgs -RedirectStandardOutput $env:E2E_STDOUT_LOG -RedirectStandardError $env:E2E_STDERR_LOG -PassThru; [System.IO.File]::WriteAllText($env:E2E_PID_FILE, [string]$process.Id) }' \
        >/dev/null || return 1

    local pid
    pid=$(tr -d '\r\n' < "${pid_file}" 2>/dev/null || true)
    [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
    echo "${pid}"
}

# 仅终止本脚本通过 Start-Process 保存的 PID；绝不终止端口检查发现的 PID。
kill_pid() {
    local pid="$1"
    local role="$2"
    if [ -z "${pid}" ] || ! [[ "${pid}" =~ ^[0-9]+$ ]]; then
        return 0
    fi

    # 防 PID 复用：仅当命令行仍携带本轮唯一 token 时才停止，身份不匹配绝不终止。
    E2E_STOP_PID="${pid}" E2E_RUN_TOKEN="${RUN_TOKEN}:${role}" \
    powershell.exe -NoProfile -NonInteractive -Command \
        '& { $details = Get-CimInstance Win32_Process -Filter "ProcessId=$($env:E2E_STOP_PID)" -ErrorAction SilentlyContinue; if ($null -eq $details) { exit 0 }; if ($details.CommandLine -notlike "*$($env:E2E_RUN_TOKEN)*") { exit 2 }; $process = Get-Process -Id $details.ProcessId -ErrorAction Stop; Stop-Process -Id $process.Id -Force; if (-not $process.WaitForExit(10000)) { exit 1 } }' \
        >/dev/null 2>&1
}

wait_for_ports_free() {
    local description="$1"
    shift
    for i in $(seq 1 30); do
        if ports_free "$@"; then
            ok "${description}"
            return 0
        fi
        sleep 1
    done
    bad "${description}"
    return 1
}

cleanup() {
    step "清理本脚本进程与测试数据"
    kill_pid "${SAMPLE_PID}" worker
    kill_pid "${ADMIN_PID}" admin
    sleep 2

    if [ "${DB_NAME}" = "job_test" ] && [[ "${JOB_IDS}" =~ ^[0-9]+(,[0-9]+)*$ ]]; then
        mysql_job_test <<SQL 2>/dev/null || true
DELETE FROM schedule_rec WHERE job_id IN (${JOB_IDS});
DELETE FROM job_change WHERE job_id IN (${JOB_IDS});
DELETE FROM job WHERE id IN (${JOB_IDS});
SQL
    elif [ "${DB_NAME}" = "job_test" ] && [ "${DATA_CLEANUP_ARMED}" = true ]; then
        # Worker 已由本轮唯一 PID 启动，但可能在记录 ID 前失败；启动前已确认目标数据不存在，故固定名称均归本轮。
        mysql_job_test <<SQL 2>/dev/null || true
DELETE FROM schedule_rec WHERE job_id IN (SELECT id FROM job WHERE name IN ('DemoJob','DemoJob2','DemoJob3','DemoJob4'));
DELETE FROM job_change WHERE job_name IN ('DemoJob','DemoJob2','DemoJob3','DemoJob4');
DELETE FROM job WHERE name IN ('DemoJob','DemoJob2','DemoJob3','DemoJob4');
DELETE FROM instance WHERE name='job-sample-server';
SQL
    fi
    # 凭证轮换 E2E 的独立身份数据（e2e-rot-app，含其作业/实例/变更源/版本）
    mysql_job_test <<SQL 2>/dev/null || true
DELETE FROM job_change WHERE job_name='e2e-rot-job';
DELETE FROM job WHERE name='e2e-rot-job';
DELETE FROM instance WHERE application_name='e2e-rot-app';
DELETE FROM credential_change WHERE application_name='e2e-rot-app';
DELETE FROM credential_version WHERE credential_id IN (SELECT id FROM credential WHERE application_name='e2e-rot-app');
DELETE FROM credential WHERE application_name='e2e-rot-app';
SQL
    if [ "${DB_NAME}" = "job_test" ] \
        && [ "${INSTANCE_NAME}" = "job-sample-server" ] \
        && [[ "${INSTANCE_HOST}" =~ ^[A-Za-z0-9:.%-]+$ ]] \
        && [ "${INSTANCE_PORT}" = "8101" ]; then
        mysql_job_test -e "DELETE FROM instance WHERE name='job-sample-server' AND host='${INSTANCE_HOST}' AND port=8101" 2>/dev/null || true
    fi
    rm -f "${ADMIN_PID_FILE}" "${SAMPLE_PID_FILE}"
    if [ "${LOCK_HELD}" = true ]; then
        rmdir "${LOCK_DIR}" 2>/dev/null || true
    fi
    echo "    本轮进程与已记录数据已清理"
}
trap cleanup EXIT

mkdir -p "${LOG_DIR}"
if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
    echo "错误：已有 E2E 冒烟测试持有锁 ${LOCK_DIR}，拒绝并发运行。" >&2
    exit 1
fi
LOCK_HELD=true

# =====================================================================
# 0. 前置检查
# =====================================================================
step "前置检查"
check "MySQL 可连接" sql_ok "SELECT 1"
check "Admin jar 存在（先执行 mvn package -DskipTests）" test -f "${ADMIN_JAR}"
check "Sample jar 存在" test -f "${SAMPLE_JAR}"
check "PowerShell 可用" command -v powershell.exe
check "cygpath 可用" command -v cygpath

if [ "${FAIL}" -ne 0 ]; then
    echo ""
    echo "前置条件不满足，先执行："
    echo "  mvn -pl admin -am package -DskipTests"
    echo "  mvn -pl samples/job-sample -am package -DskipTests"
    echo "并确保 job_test 库已初始化。"
    exit 1
fi

# 固定注解名无法动态隔离测试数据；发现目标数据时拒绝运行，绝不清理未知存量。
fatal_check "目标测试数据不存在（job/job_change/instance 三类均为 0）" db_data_absent

# 启动前检查端口占用，仅报告 PID，不终止未知进程。
for port in "${ADMIN_PORT}" "${SAMPLE_PORT}" 8101; do
    if ! ports_free "${port}"; then
        port_pids=$(listening_pids "${port}")
        echo "错误：端口 ${port} 已被占用，PID=${port_pids}。请先手工处理后重试。" >&2
        exit 1
    fi
done

# =====================================================================
# 1. 启动 Admin
# =====================================================================
step "启动 Admin（连 ${DB_NAME} 库，端口 ${ADMIN_PORT}）"
ADMIN_URL="jdbc:mysql://localhost:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
ADMIN_JAR_WIN=$(windows_path "${ADMIN_JAR}")
if ! ADMIN_PID=$(start_java admin "${ADMIN_PID_FILE}" "${ADMIN_LOG}" "${ADMIN_ERR_LOG}" \
    -jar "${ADMIN_JAR_WIN}" \
    "--server.port=${ADMIN_PORT}" \
    "--spring.datasource.url=${ADMIN_URL}" \
    "--spring.datasource.username=${DB_USER}" \
    "--spring.datasource.password=${DB_PASS}" \
    "--schedule.credential-seed=job-sample-server:default:defaultToken" \
    "--schedule.admin.password=e2e-password"); then
    bad "Admin 进程启动"
    exit 1
fi
ok "Admin 进程启动"
echo "    Admin PID=${ADMIN_PID}，日志 ${ADMIN_LOG}"

for i in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${ADMIN_PORT}/actuator/health" 2>/dev/null)
    if [ "${code}" = "200" ]; then break; fi
    sleep 2
done
fatal_check "Admin 健康检查通过" admin_healthy
fatal_check "Admin 端口由启动返回 PID 持有" port_owned "${ADMIN_PORT}" "${ADMIN_PID}"

# =====================================================================
# 2. 启动 Worker（job-sample）
# =====================================================================
start_worker() {
    SAMPLE_JAR_WIN=$(windows_path "${SAMPLE_JAR}")
    SAMPLE_PID=$(start_java worker "${SAMPLE_PID_FILE}" "${SAMPLE_LOG}" "${SAMPLE_ERR_LOG}" \
        -jar "${SAMPLE_JAR_WIN}" \
        "--server.port=${SAMPLE_PORT}" \
        "--schedule-job.server-address=http://localhost:${ADMIN_PORT}")
}

wait_for_worker() {
    local description="$1"
    for i in $(seq 1 30); do
        if worker_started; then
            break
        fi
        sleep 2
    done
    fatal_check "${description}" worker_started
}

step "启动 Worker（job-sample，端口 ${SAMPLE_PORT}）"
if ! start_worker; then
    bad "Worker 进程启动"
    exit 1
fi
DATA_CLEANUP_ARMED=true
ok "Worker 进程启动"
echo "    Sample PID=${SAMPLE_PID}，日志 ${SAMPLE_LOG}"
wait_for_worker "Worker 启动完成且 HTTP/Netty 端口均由启动返回 PID 持有"

# =====================================================================
# 3. 验证注册与调度执行
# =====================================================================
step "验证实例注册（GROUP 模式只注册 1 个实例）"
sleep 5
check "instance 表恰 1 行 job-sample-server" \
    count_eq 1 "SELECT COUNT(*) FROM instance WHERE name='job-sample-server'"
INSTANCE_ROW=$(mysql_job_test -N -e "SELECT CONCAT_WS('|', name, host, port) FROM instance WHERE name='job-sample-server'")
IFS='|' read -r INSTANCE_NAME INSTANCE_HOST INSTANCE_PORT <<< "${INSTANCE_ROW}"
fatal_check "记录本轮 Worker 精确实例键" instance_key_valid

step "验证作业注册（4 个 DemoJob）"
check "job 表 4 行 DemoJob" jobs_registered
JOB_IDS=$(mysql_job_test -N -e "SELECT GROUP_CONCAT(id ORDER BY id) FROM job WHERE name IN ('DemoJob','DemoJob2','DemoJob3','DemoJob4')")
fatal_check "记录本轮 4 个 DemoJob ID" job_ids_valid_4
check "job_change 4 条 change_type=1（注册埋点）" changes_registered

step "验证调度触发并执行成功（等 15s 覆盖 DemoJob4 的 1s cron）"
sleep 15
check "DemoJob 的 schedule_rec 存在成功记录（status=1）" \
    count_gt 0 "SELECT COUNT(*) FROM schedule_rec WHERE status=1 AND job_id IN (${JOB_IDS})"
check "DemoJob 的成功记录携带 execute_result" \
    count_gt 0 "SELECT COUNT(*) FROM schedule_rec WHERE status=1 AND execute_result IS NOT NULL AND job_id IN (${JOB_IDS})"

# =====================================================================
# 4. 重启 Worker 验证幂等（条件插入去重）
# =====================================================================
step "重启 Worker，验证重复注册幂等"
SUCCESS_COUNT_BEFORE_RESTART=$(mysql_job_test -N -e "SELECT COUNT(*) FROM schedule_rec WHERE status=1 AND job_id IN (${JOB_IDS})")
kill_pid "${SAMPLE_PID}" worker
SAMPLE_PID=""
wait_for_ports_free "旧 Worker 的 HTTP/Netty 端口已释放" "${SAMPLE_PORT}" 8101 || exit 1
if ! start_worker; then
    bad "Worker 重启进程启动"
    exit 1
fi
wait_for_worker "Worker 重启完成且 HTTP/Netty 端口均由新 PID 持有"
sleep 5

check "重启后 job 行数仍为 4（不新增）" \
    count_eq 4 "SELECT COUNT(*) FROM job WHERE id IN (${JOB_IDS})"
check "重启后 change_type=1 记录仍为 4（不新增变更）" \
    count_eq 4 "SELECT COUNT(*) FROM job_change WHERE change_type=1 AND job_id IN (${JOB_IDS})"
check "重启后 instance 仍为 1（不重复注册）" \
    instance_row "${INSTANCE_NAME}" "${INSTANCE_HOST}" "${INSTANCE_PORT}"
check "重启后 DemoJob 调度成功记录严格增加" \
    count_gt "${SUCCESS_COUNT_BEFORE_RESTART}" "SELECT COUNT(*) FROM schedule_rec WHERE status=1 AND job_id IN (${JOB_IDS})"

# =====================================================================
# 5. 逻辑删除作业不被复活
# =====================================================================
step "逻辑删除 DemoJob，重启 Worker 验证不复活"
fatal_check "逻辑删除 DemoJob SQL 执行成功" \
    sql_ok "UPDATE job SET deleted=1 WHERE name='DemoJob' AND id IN (${JOB_IDS})"
kill_pid "${SAMPLE_PID}" worker
SAMPLE_PID=""
wait_for_ports_free "旧 Worker 的 HTTP/Netty 端口已释放" "${SAMPLE_PORT}" 8101 || exit 1
if ! start_worker; then
    bad "逻辑删除后的 Worker 重启进程启动"
    exit 1
fi
wait_for_worker "逻辑删除后的 Worker 重启完成且 HTTP/Netty 端口均由新 PID 持有"
sleep 5

check "DemoJob 仍只有 1 行且 deleted=1（不复活）" job_deleted_row "DemoJob"
check "其余作业仍为 3 行（未受影响）" \
    count_eq 3 "SELECT COUNT(*) FROM job WHERE name IN ('DemoJob2','DemoJob3','DemoJob4') AND deleted=0 AND id IN (${JOB_IDS})"

# =====================================================================
# 6. 凭证管理轮换全链路（独立身份 e2e-rot-app，不干扰 Worker 主链路）
# =====================================================================
step "凭证管理：登录 → prepare → 双版本并行 → activate → revoke（e2e-rot-app）"
ADMIN_BASE="http://localhost:${ADMIN_PORT}"

# 6.1 登录获取会话 token
LOGIN_RESP=$(curl -s -X POST "${ADMIN_BASE}/admin/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"e2e-password"}')
ADMIN_TOKEN=$(echo "${LOGIN_RESP}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
check "管控登录成功并返回会话 token" test -n "${ADMIN_TOKEN}"
fatal_check "未登录访问 /admin/** 被拒（401）" \
    curl_test "401" -X POST "${ADMIN_BASE}/admin/credential/prepare" \
    -H 'Content-Type: application/json' \
    -d '{"applicationName":"e2e-rot-app","env":"default"}'

# 6.2 prepare 首建：无身份 → 直接 ACTIVE v1，明文返回一次
RESP1=$(curl -s -X POST "${ADMIN_BASE}/admin/credential/prepare" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"applicationName":"e2e-rot-app","env":"default"}')
TOKEN1=$(echo "${RESP1}" | grep -o '"plaintext":"[^"]*"' | cut -d'"' -f4)
check "prepare 返回明文凭证（仅此一次）" test -n "${TOKEN1}"
check "首建版本为 v1" echo "${RESP1}" | grep -q '"version":1'

# 6.3 prepare 轮换：有 active → PENDING v2，明文返回一次
RESP2=$(curl -s -X POST "${ADMIN_BASE}/admin/credential/prepare" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"applicationName":"e2e-rot-app","env":"default"}')
TOKEN2=$(echo "${RESP2}" | grep -o '"plaintext":"[^"]*"' | cut -d'"' -f4)
check "轮换 prepare 返回新明文" test -n "${TOKEN2}"
check "轮换版本为 v2" echo "${RESP2}" | grep -q '"version":2'

# 6.4 过渡期双版本并行：新旧明文均可通过 /open/** 鉴权（注册作业）
check "新明文可注册作业（/open 200）" \
    curl_test "200" -X POST "${ADMIN_BASE}/open/job/register" \
    -H 'X-Job-Group: e2e-rot-app' -H 'X-Job-Env: default' \
    -H "Authorization: Bearer ${TOKEN2}" \
    -H 'Content-Type: application/json' \
    -d '{"group":"e2e-rot-app","jobname":"e2e-rot-job","cron":"0 0/5 * * * ?","type":0,"strategy":1}'
check "旧明文仍可注册作业（/open 200）" \
    curl_test "200" -X POST "${ADMIN_BASE}/open/job/register" \
    -H 'X-Job-Group: e2e-rot-app' -H 'X-Job-Env: default' \
    -H "Authorization: Bearer ${TOKEN1}" \
    -H 'Content-Type: application/json' \
    -d '{"group":"e2e-rot-app","jobname":"e2e-rot-job","cron":"0 0/5 * * * ?","type":0,"strategy":1}'

# 6.5 activate：无在线实例视为就绪；旧版立即吊销
ACT_RESP=$(curl -s -X POST "${ADMIN_BASE}/admin/credential/activate" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"applicationName":"e2e-rot-app","env":"default","force":false,"reason":""}')
check "activate 生效版本为 v2" echo "${ACT_RESP}" | grep -q '"activatedVersion":2'
check "旧明文已失效（/open 401）" \
    curl_test "401" -X POST "${ADMIN_BASE}/open/job/register" \
    -H 'X-Job-Group: e2e-rot-app' -H 'X-Job-Env: default' \
    -H "Authorization: Bearer ${TOKEN1}" \
    -H 'Content-Type: application/json' \
    -d '{"group":"e2e-rot-app","jobname":"e2e-rot-job","cron":"0 0/5 * * * ?","type":0,"strategy":1}'
check "新明文仍有效（/open 200）" \
    curl_test "200" -X POST "${ADMIN_BASE}/open/job/register" \
    -H 'X-Job-Group: e2e-rot-app' -H 'X-Job-Env: default' \
    -H "Authorization: Bearer ${TOKEN2}" \
    -H 'Content-Type: application/json' \
    -d '{"group":"e2e-rot-app","jobname":"e2e-rot-job","cron":"0 0/5 * * * ?","type":0,"strategy":1}'

# 6.6 revoke：Fail-Closed（新明文也失效），DB 清空密码材料
REVOKE_RESP=$(curl -s -X POST "${ADMIN_BASE}/admin/credential/revoke" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"applicationName":"e2e-rot-app","env":"default","reason":"e2e verification"}')
check "revoke 吊销版本为 v2" echo "${REVOKE_RESP}" | grep -q '"revokedVersion":2'
check "吊销后明文失效（/open 401）" \
    curl_test "401" -X POST "${ADMIN_BASE}/open/job/register" \
    -H 'X-Job-Group: e2e-rot-app' -H 'X-Job-Env: default' \
    -H "Authorization: Bearer ${TOKEN2}" \
    -H 'Content-Type: application/json' \
    -d '{"group":"e2e-rot-app","jobname":"e2e-rot-job","cron":"0 0/5 * * * ?","type":0,"strategy":1}'
check "吊销后 active 指针为空（Fail-Closed）" \
    sql_ok "SELECT 1 FROM credential WHERE application_name='e2e-rot-app' AND active_version IS NULL"
check "终态版本已清空 token_hash" \
    sql_ok "SELECT 1 FROM credential_version v JOIN credential c ON v.credential_id=c.id WHERE c.application_name='e2e-rot-app' AND v.token_hash IS NULL"

# =====================================================================
# 汇总
# =====================================================================
echo ""
echo "======================================================"
echo "端到端冒烟测试完成：PASS=${PASS} FAIL=${FAIL}"
echo "======================================================"
echo "日志：${ADMIN_LOG} / ${SAMPLE_LOG}"
[ "${FAIL}" -eq 0 ]
