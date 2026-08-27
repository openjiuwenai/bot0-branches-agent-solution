#!/usr/bin/env bash
# 部署契约面的兼容验证（兼容面清单第八维）：
#   拿**存量的 .env.example 原样**起本版，验它能起来、能对外服务、
#   且不承接的那几项打了可见告警。
#
# ## 它验什么，run-parity.sh 不验什么
#
# run-parity.sh 起两侧真实服务比对**响应字节**——那是调用方视角。
# 本脚本验的是**部署方视角**：手里那份 .env、那条启动命令，换了 runtime 还能不能用。
#
# 此前「对外兼容」只盘调用方那一面，于是 wire 上做到了逐字节，而部署方拿本版
# 换掉存量时起不来。清单漏了一整面，验证也就漏了同一面——本脚本补上。
#
# ## 三项断言
#
#   1. 用存量 .env 起得来（进程活、端口通）
#   2. 对外能服务（健康端点应答）
#   3. 不承接的项打了可见告警，归宿主的项没打（后者会把真问题淹掉）
#
# 确定性（无 LLM、无外部依赖）。exit 0=过 / 1=不过 / 3=未判（存量未导出）。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# **解释器定位交给 `_backend.sh` 的 `_e2e_python`**，不硬编码 `.venv/bin/python`。
#
# CI 用 `actions/setup-python` 加 `pip install`，**runner 上没有 `.venv`**——硬编码那条
# 路径时启动命令直接失败（`env: .../.venv/bin/python: No such file or directory`），
# 脚本报「用存量 .env 起不来」，而真实原因与存量 `.env` 毫无关系。
# 本机一直有 `.venv`，所以从写下就一路绿，**第一次进 CI 就红**。
# 同目录的 `_backend.sh` 早为此写了 `_e2e_python`（`.venv` 优先、退回 `python3`），我没复用。
source "$HERE/_backend.sh"
ORACLE="$ROOT/.legacy-oracle/applications/a2a_service"
PORT="${LEGACY_ENV_PARITY_PORT:-18099}"
LOG="$(mktemp)"
PID=""

log() { echo "[e2e-legacy-env] $*"; }

cleanup() {
    [ -n "$PID" ] && kill "$PID" 2>/dev/null
    rm -f "$LOG"
}
trap cleanup EXIT INT TERM

# ── 前置：存量副本要在 ──────────────────────────────────────
if [ ! -f "$ORACLE/.env.example" ]; then
    log "存量副本未导出（缺 $ORACLE/.env.example）。"
    log "先跑 tools/legacy_oracle.sh fetch；**未判不等于通过**。"
    exit 3
fi

log "1/4 以存量 .env.example 为基底构造环境"
# **原样取存量的变量名与默认值**，只覆盖三项：端口（避开占用）、后端（不要真模型）、
# 以及一项不承接的变量（要验它确实告警）。
ENV_ARGS=()
while IFS= read -r line; do
    case "$line" in
        ''|\#*) continue ;;
    esac
    name="${line%%=*}"
    value="${line#*=}"
    # 存量示例里的占位值多为空或形如 your-xxx；空值原样传，让本版走自己的默认
    ENV_ARGS+=("$name=$value")
done < "$ORACLE/.env.example"
log "   取到 ${#ENV_ARGS[@]} 项存量变量"

log "2/4 起本版（入口只改模块名，命令形态与存量对齐）"
env "${ENV_ARGS[@]}" \
    FASTAPI_HOST=127.0.0.1 FASTAPI_PORT="$PORT" FASTAPI_WORKERS=1 \
    RUNTIME_BACKEND=fixture \
    BOOTSTRAP_COORDINATION_ENABLED=true \
    PYTHONPATH="$ROOT:$ROOT/deploy${PYTHONPATH:+:$PYTHONPATH}" \
    "$(_e2e_python)" -m agent_runtime.bootstrap.legacy_compat \
    > "$LOG" 2>&1 &
PID=$!

log "3/4 等待就绪（最多 60s）"
ready=0
for _ in $(seq 1 60); do
    if ! kill -0 "$PID" 2>/dev/null; then
        log "❌ 进程已退出。用存量 .env 起不来——这正是本维要防的形态。"
        log "   最后 30 行输出："
        tail -30 "$LOG" | sed 's/^/     /'
        exit 1
    fi
    if curl -sf -m 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
        ready=1; break
    fi
    sleep 1
done
if [ "$ready" -ne 1 ]; then
    log "❌ 60s 内未就绪。最后 30 行输出："
    tail -30 "$LOG" | sed 's/^/     /'
    exit 1
fi
log "   ✅ 起得来且对外应答"

log "4/4 验告警分界：不承接的要告警，归宿主的不告警"
python3 - "$LOG" <<'PY' || exit 1
import sys, pathlib
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")

# ① 不承接的项设置了就要有可见告警。**静默失效是升级场景里最危险的形态**：
#    用户以为配了，实际没生效，且不报错。
assert "BOOTSTRAP_COORDINATION_ENABLED" in text, (
    "设置了 BOOTSTRAP_COORDINATION_ENABLED 却没有任何告警——"
    f"用户不会知道它不生效。日志片段：{text[-600:]}"
)
assert "不承接" in text, f"告警没说清为什么不生效：{text[-600:]}"

# ② 归宿主的项**不该**告警。为它们告警会把真问题淹掉——用户每次启动看到十几条
#    无关告警就不会再读，而真正不生效的那几条恰恰混在里面。
for quiet in ("FASTAPI_PORT", "LOG_LEVEL", "RATE_LIMIT_MAX_REQUESTS"):
    assert f"存量变量 {quiet}" not in text, (
        f"{quiet} 归宿主，不该为它告警——那会淹掉真问题"
    )

print("[e2e-legacy-env] ✅ 告警分界正确：不承接的告警了，归宿主的没告警")
PY

log "✅ 部署契约面兼容：存量 .env 原样可用，告警分界正确"
exit 0
