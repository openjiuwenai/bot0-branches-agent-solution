#!/usr/bin/env bash
# 部署级 E2E 的服务启动后端 —— 被各 run-*.sh 共用。
#
# ## 为什么要有两种后端
#
# 容器后端是原本唯一的形态，但部分设备无法安装容器运行时。真正不能丢的是
# **真 socket 往返**：进程内测试全部走 ASGI 传输、不经网络栈，而本项目已有多次实证，
# wire 契约缺陷恰恰只在真 socket 下暴露（终答被完成信号吞掉、首帧即中断时错误码不对、
# 端侧工具投影丢空串——三例单测全绿）。
#
# 本机进程后端保住的正是这一条：uvicorn 监听真端口，curl 发真 HTTP 请求，
# 真实的 a2a-sdk 在两端。**它不是容器验证的等价物**，见下方「各自覆盖什么」。
#
# ## 各自覆盖什么 —— 是交叉关系，不是包含关系
#
# | 维度 | 容器 | 本机进程 |
# |---|---|---|
# | 真 socket、真 HTTP 栈、真 SDK 往返 | ✓ | ✓ |
# | 干净依赖环境（暴露漏声明的依赖） | ✓ | ✗ 用开发环境，dev 依赖也在 |
# | Dockerfile 与启动命令本身正确 | ✓ | ✗ 完全不涉及 |
# | **对固定环境的硬编码**（端口/地址/路径） | **✗** | **✓** |
#
# 最后一行反直觉但已实证：容器里服务固定监听 8090，于是「自指地址写死 8090」这类
# 缺陷**恰好自洽、容器永远抓不到**；本机后端换个端口一跑就暴露。实测过——把该硬编码
# 注入回去，容器后端通过、本机后端失败。**环境越固定，越纵容对该环境的硬编码。**
#
# 所以两者不是替代关系：
#
# - 日常开发内循环用本机（更快，且抓得住硬编码）
# - 提交前两个都跑；**只跑容器会漏掉最后一行那一整类**
#
# 中间两项是本机后端的真实欠账，不得声称已验证。
#
# ## 用法
#
#   source "$(dirname "${BASH_SOURCE[0]}")/_backend.sh"
#   e2e_start <module> <port>      # 产出 $BASE，两种后端一致
#   e2e_wait_health                # 等待就绪，失败时自动打印诊断
#   ...此后的断言与后端无关，各脚本自己写...
#   （teardown 已由 trap 自动挂上）
#
# 后端选择：环境变量 E2E_BACKEND=docker|local|auto（默认 auto —— 有可用容器运行时
# 就用容器，否则退到本机进程，并明确告知欠了哪两项）。

E2E_BACKEND="${E2E_BACKEND:-auto}"
E2E_IMAGE="${E2E_IMAGE:-agent-runtime-e2e:local}"
E2E_CONTAINER="${E2E_CONTAINER:-agent-runtime-e2e-run}"

_E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
_E2E_LOGDIR="$_E2E_ROOT/deploy-e2e/.e2e-logs"
_E2E_PID=""
_E2E_LOG=""
_E2E_RESOLVED=""

e2e_log() { echo "[e2e] $*"; }

# ── 后端判定 ──────────────────────────────────────────────────────

_e2e_resolve_backend() {
    [ -n "$_E2E_RESOLVED" ] && return
    case "$E2E_BACKEND" in
        docker|local) _E2E_RESOLVED="$E2E_BACKEND" ;;
        auto)
            # 光有 docker 命令不够——守护进程没起时命令在、调用必失败。
            if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
                _E2E_RESOLVED="docker"
            else
                _E2E_RESOLVED="local"
            fi
            ;;
        *) e2e_log "❌ E2E_BACKEND 取值非法：$E2E_BACKEND（可选 docker|local|auto）"; exit 2 ;;
    esac
    if [ "$_E2E_RESOLVED" = "local" ]; then
        e2e_log "后端：本机进程（真 socket 往返）"
        e2e_log "  ⚠ 未覆盖：干净依赖环境、Dockerfile 与启动命令本身。这两项仍是欠账。"
    else
        e2e_log "后端：容器"
    fi
}

# ── 解释器定位（本机后端用）────────────────────────────────────────

_e2e_python() {
    # 仓内虚拟环境优先，两种平台布局都试；都没有才退到 PATH 上的解释器。
    for p in "$_E2E_ROOT/.venv/bin/python" "$_E2E_ROOT/.venv/Scripts/python.exe"; do
        [ -x "$p" ] && { echo "$p"; return; }
    done
    command -v python3 >/dev/null 2>&1 && { echo "python3"; return; }
    echo "python"
}

_e2e_path_for() {
    # 把路径转成目标解释器认得的形式。
    #
    # Git Bash 下的路径是 MSYS 形式（/c/Users/...），而 .venv\Scripts\python.exe 是
    # **Windows 原生解释器**，不认这种路径——PYTHONPATH 传过去等于没传，
    # 表现是「找不到 agent_runtime」，看起来像装漏了依赖。
    # WSL 里解释器与路径同为 Linux 形式，不需要转换。
    local py="$1" raw="$2"
    case "$py" in
        *.exe) command -v cygpath >/dev/null 2>&1 && cygpath -w "$raw" || echo "$raw" ;;
        *)     echo "$raw" ;;
    esac
}

# ── 启动 ──────────────────────────────────────────────────────────

e2e_start() {
    local module="${1:?缺少 E2E 模块名}" port="${2:-18090}"
    _e2e_resolve_backend
    e2e_teardown
    if [ "$_E2E_RESOLVED" = "docker" ]; then
        _e2e_start_docker "$module" "$port"
    else
        _e2e_start_local "$module" "$port"
    fi
}

_e2e_start_docker() {
    local module="$1" port="$2"
    e2e_log "构建镜像..."
    docker build -f "$_E2E_ROOT/deploy-e2e/Dockerfile" -t "$E2E_IMAGE" "$_E2E_ROOT" >/dev/null \
        || { e2e_log "❌ 镜像构建失败"; exit 1; }
    e2e_log "启动容器（模块 $module）..."
    # 先试 host 网络（真模型变体需连外部网关），失败退到端口映射。
    # **回退不可省**：部分平台的容器运行时跑在虚拟机里，host 网络未显式开启时
    # 容器正常启动、日志正常，但宿主连不上端口——症状是健康检查超时配一份健康的日志，
    # 会把诊断引向完全错误的方向。
    # **按清单透传环境变量**：容器默认只有 E2E_MODULE，各脚本设的变量进不去，
    # 服务在容器里会跑成另一套配置——本机后端绿、容器后端红，差异只在这里。
    # E2E_PASS_ENV 由调用方设为空格分隔的变量名清单。
    _E2E_ENV_ARGS=()
    for _name in ${E2E_PASS_ENV:-}; do
        _value="${!_name:-}"
        [ -n "$_value" ] && _E2E_ENV_ARGS+=(-e "$_name=$_value")
    done

    # **host 网络那一支不能只看 `docker run` 的退出码**。
    #
    # host 网络下容器名不影响端口绑定：8090 已被别的进程占着时，`docker run -d`
    # 照样返回成功（容器建起来了），而里面的 uvicorn 绑不上端口随即退出。
    # 此后 `BASE=http://127.0.0.1:8090` 指向的是**占着那个端口的别人**——
    # 场景脚本连上去、拿到 200、断言逐条失败，报出来的是
    # 「一个产物都没有」「Not Found」「KeyError」这类**看起来像自己代码坏了**的读数，
    # 而不是「端口被占」。2026-08-27 实测：另一棵工作树的容器占着 8090 时，
    # 本机 24 道门禁里 8 条部署级场景以这种形态红掉，排查了三轮才查到端口归属。
    #
    # 故起完之后**确认容器还活着**：绑不上端口的容器此时已经退出，
    # 那就删掉它、退回下面发布端口那一支。
    # 第一道：**开容器之前先看 8090 有没有人在听**。无时序、无约定、无特权——
    # `ss -lntH` 不带 `-p`，只问「有没有 LISTEN」，普通用户拿得到；
    # 而「是谁在听」需要特权（容器进程归 root，`users:` 那一段整个不显示），
    # 所以按 PID 比对归属这条路在本机的运行身份下做不成（两侧实测确认）。
    #
    # `E2E_HOST_NETWORK=0` 是给「要零竞态」的场合的显式开关：并发期各方自己声明，
    # 连探测都不做。**默认路径不依赖任何人记得声明**——约定失效是必然事件，
    # 竞态是概率事件，故默认取自愈而不取约定。
    if [ "${E2E_HOST_NETWORK:-1}" != "0" ] \
       && ! ss -lntH "sport = :8090" 2>/dev/null | grep -q . \
       && docker run -d --name "$E2E_CONTAINER" --network host \
            -e "E2E_MODULE=$module" "${_E2E_ENV_ARGS[@]}" "$E2E_IMAGE" >/dev/null 2>&1 \
       && _e2e_container_survived; then
        BASE="http://127.0.0.1:8090"
    else
        docker rm -f "$E2E_CONTAINER" >/dev/null 2>&1
        docker run -d --name "$E2E_CONTAINER" -p "$port:8090" \
            -e "E2E_MODULE=$module" "${_E2E_ENV_ARGS[@]}" "$E2E_IMAGE" >/dev/null 2>&1 \
            || { e2e_log "❌ 容器启动失败"; exit 1; }
        BASE="http://127.0.0.1:$port"
    fi
}

_e2e_container_survived() {
    # 第二道网：轮询到「容器退出」或「超时」，**判据是状态不是定值等待**。
    #
    # 前一版写 `sleep 2` 然后看一次是否 Running。那是定值等待：机器负载高时
    # 绑不上端口的容器晚死零点几秒，就会在第 2 秒被判成活着，于是 BASE 又写死
    # 8090、又读到别人的服务——**失败形态与修复前完全一样，只是概率低了**，
    # 而低概率的假绿比高概率的更难查。
    local i state
    for i in $(seq 1 20); do
        state="$(docker inspect -f '{{.State.Running}}' "$E2E_CONTAINER" 2>/dev/null)"
        [ "$state" = "false" ] && return 1     # 已退出：多半是绑不上端口
        [ -z "$state" ] && return 1            # 容器都不在了
        curl -sf -m 1 "http://127.0.0.1:8090/health" >/dev/null 2>&1 && return 0
        sleep 0.5
    done
    # **超时而容器仍活着：认它**。host 网络下绑不上端口的容器会很快退出——
    # 活过整个轮询窗口就说明端口是它的。健康端点不是每个 E2E 变体都有，
    # 拿「探不到健康」当「没起来」会让每个没有该端点的场景白等一轮再多起一次容器。
    #
    # 判定的落点始终是**容器死没死**（可观测事实），健康探测只是让活着的那种早点返回。
    [ "$(docker inspect -f '{{.State.Running}}' "$E2E_CONTAINER" 2>/dev/null)" = "true" ]
}

_e2e_start_local() {
    local module="$1" port="$2"
    local py; py="$(_e2e_python)"
    # 先确认解释器装了运行时依赖。不查的话，缺依赖时用户看到的是一屏 traceback，
    # 而真正的原因（没建虚拟环境 / 没装依赖）埋在最后一行——第一次跑的人根本看不出来。
    if ! "$py" -c "import openjiuwen, a2a, uvicorn" >/dev/null 2>&1; then
        e2e_log "❌ 解释器 $py 缺运行时依赖，无法起服务。"
        e2e_log "   多半是没建虚拟环境或没装依赖。在仓根执行："
        e2e_log "     python -m venv .venv && . .venv/bin/activate"
        e2e_log "     pip install -r agent_runtime/requirements-dev.txt && pip install -e . --no-deps"
        e2e_log "   （Windows 下激活命令是 .venv\\Scripts\\activate）"
        exit 1
    fi
    mkdir -p "$_E2E_LOGDIR"
    _E2E_LOG="$_E2E_LOGDIR/$module.log"
    e2e_log "启动本机进程（模块 $module，解释器 $py）..."
    # 与容器镜像的启动命令**同构**：容器里 WORKDIR /app、服务模块与 agent_runtime 都在 /app 下，
    # 启动命令是 `uvicorn <module>:app`。这里以 deploy-e2e 为工作目录取得同样的模块名，
    # 再用 PYTHONPATH 把仓根加进导入路径，让 agent_runtime 可导入。
    #
    # **不写成 `uvicorn deploy-e2e.<module>:app`**：`deploy-e2e` 含连字符，不是合法的
    # Python 包名，那样能跑是靠实现的宽容而非规范，换个版本就可能失效。
    # PORT 传给服务模块：有两个变体需要自指（连回自己起的 mock 平台 / 对自身发起远端调用），
    # 它们默认按容器里的 8090 自指，本机后端端口不同，不传就会连到没人监听的端口。
    # **exec 不可省**：没有它时 $! 拿到的是子 shell 的进程号而非服务本身，
    # 收尾时杀掉子 shell 而服务活着——端口被占、**旧代码的进程继续应答**，
    # 下一次运行测到的是上一次的代码。这个形态一次就制造了三个假失败。
    local pypath; pypath="$(_e2e_path_for "$py" "$_E2E_ROOT")"
    ( cd "$_E2E_ROOT/deploy-e2e" && PYTHONPATH="$pypath${PYTHONPATH:+:$PYTHONPATH}" \
        PORT="$port" exec "$py" -m uvicorn "$module:app" \
        --host 127.0.0.1 --port "$port" > "$_E2E_LOG" 2>&1 ) &
    _E2E_PID=$!
    BASE="http://127.0.0.1:$port"
}

# ── 就绪等待 ──────────────────────────────────────────────────────

#: 取当前后端的日志（容器或本机进程），供就绪失败时判「是不是外部依赖不可用」。
_e2e_logs() {
    if [ "$_E2E_RESOLVED" = "docker" ]; then
        docker logs "$E2E_CONTAINER" 2>&1
    elif [ -n "$_E2E_LOG" ] && [ -f "$_E2E_LOG" ]; then
        cat "$_E2E_LOG"
    fi
}

e2e_wait_health() {
    local timeout="${1:-60}" i
    e2e_log "等待 /health（最多 ${timeout}s）..."
    for i in $(seq 1 "$timeout"); do
        curl -sf -m 3 "$BASE/health" >/dev/null 2>&1 && { e2e_log "  health OK"; return 0; }
        # 服务已经死了就别再等满超时——白等一分钟只会让人以为是慢，不是崩了。
        _e2e_alive || {
            # **服务没起来：分清「缺外部依赖」与「我方代码坏了」**。
            #
            # 前者是「这一维没查成」（退出码 3 = 未判），后者是真失败（1）。
            # 特征词判定只用在这一刻——此时日志里只有启动过程，
            # 不含任何断言输出，不会被失败消息里的字眼误导。
            # （门禁那一层曾按整份日志的特征词猜未判，被攻破两次。）
            if _e2e_logs 2>/dev/null | grep -qiE "model service config error|api key|no api key|connection refused|name or service not known"; then
                e2e_log "⏭ 服务未能启动：外部依赖不可用，本维未查成"
                e2e_diag
                exit 3
            fi
            e2e_log "❌ 服务已退出"; e2e_diag; exit 1
        }
        sleep 1
    done
    e2e_log "❌ /health 超时"; e2e_diag; exit 1
}

_e2e_alive() {
    if [ "$_E2E_RESOLVED" = "docker" ]; then
        docker ps -q -f "name=$E2E_CONTAINER" | grep -q .
    else
        [ -n "$_E2E_PID" ] && kill -0 "$_E2E_PID" 2>/dev/null
    fi
}

# ── 诊断与收尾 ────────────────────────────────────────────────────

e2e_diag() {
    if [ "$_E2E_RESOLVED" = "docker" ]; then
        docker logs "$E2E_CONTAINER" 2>&1 | tail -30
    elif [ -n "$_E2E_LOG" ] && [ -f "$_E2E_LOG" ]; then
        tail -30 "$_E2E_LOG"
    fi
}

e2e_teardown() {
    if [ "$_E2E_RESOLVED" = "docker" ]; then
        docker rm -f "$E2E_CONTAINER" >/dev/null 2>&1 || true
    elif [ -n "$_E2E_PID" ]; then
        kill "$_E2E_PID" 2>/dev/null || true
        wait "$_E2E_PID" 2>/dev/null || true
        # 确认真的退出了。端口释放有延迟时，下一次启动会撞上「地址已被占用」，
        # 而那次失败看起来像被测代码的问题。
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$_E2E_PID" 2>/dev/null || break
            sleep 0.2
        done
        kill -9 "$_E2E_PID" 2>/dev/null || true
        _E2E_PID=""
    fi
}

trap e2e_teardown EXIT
