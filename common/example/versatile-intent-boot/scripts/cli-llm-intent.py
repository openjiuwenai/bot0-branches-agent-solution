#!/usr/bin/env python3
#
# CLI client for the LLM intent demo (versatile-intent-boot).
#
# Mirrors the shape of a2a-samples' helloworld/test_client.py: fetch & display
# the agent card, send streaming / non-streaming messages, replay the scripted
# scenarios from local-e2e-llm-intent.sh, and drop into an interactive chat
# loop. `start` additionally launches the whole demo stack (gateway + L1 +
# L2_hotel + L2_flight + Agent B hotel/flight) and tears it down on exit.
#
# Stdlib-only — no httpx / a2a-sdk dependency, runs with plain `python3`.
#
# LLM parameters (LLM_API_KEY / LLM_BASE_URL / LLM_MODEL, DEEPSEEK_*) are read
# from a `.env` file next to this module (see `.env.example`); real environment
# variables take precedence. They are only used by `start` to launch the java
# processes — the client commands (card/scenario/chat) just talk to L1 over HTTP.
#
# Usage:
#     python3 scripts/cli-llm-intent.py start                       # launch stack + interactive chat
#     python3 scripts/cli-llm-intent.py start --scenario all        # launch stack + replay A->B->C, then exit
#     python3 scripts/cli-llm-intent.py start --no-build            # reuse existing jars
#     python3 scripts/cli-llm-intent.py card                        # show L1 agent card (stack must be up)
#     python3 scripts/cli-llm-intent.py scenario a                  # replay scenario A
#     python3 scripts/cli-llm-intent.py scenario all               # replay A -> B -> C
#     python3 scripts/cli-llm-intent.py chat                        # interactive loop (non-streaming)
#     python3 scripts/cli-llm-intent.py chat --stream               # interactive loop (SSE streaming)
#     python3 scripts/cli-llm-intent.py --base-url http://host:8081 chat
#
# Options:
#     --base-url URL         L1 base URL (default http://localhost:8081)
#     --conversation-id ID   conversation id shared across turns (default c-llm-demo)
#     --user-id ID           user id sent in the query body (default u-42)
#     --stream               request streaming SSE responses (chat / scenario)
#     --env-file PATH        extra .env file to load (default <module>/.env)
#     -h, --help             show this help

import argparse
import atexit
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULE_DIR = os.path.dirname(SCRIPT_DIR)
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(MODULE_DIR)))
JAR_FILE = os.path.join(MODULE_DIR, "target", "versatile-intent-boot-0.1.0.jar")
AGENT_B_MODULE = os.path.join(
    REPO_ROOT, "common", "example", "agentcore-ext-deepagent-remote-a2a-demo",
    "agent-b-deepagent-runtime")
AGENT_B_JAR = os.path.join(AGENT_B_MODULE, "target", "deepagent-remote-a2a-agent-b-0.1.0.jar")
LOG_DIR = os.path.join(MODULE_DIR, "target")

DEFAULT_BASE_URL = "http://localhost:8081"
DEFAULT_CONVERSATION_ID = "c-llm-demo"
DEFAULT_USER_ID = "u-42"
AGENT_CARD_PATH = "/.well-known/agent-card.json"
QUERY_PATH = "/v1/query"
BIZ_TAG = "llm-demo"

PORT_DEFAULTS = {
    "GATEWAY_PORT": "8084",
    "L1_PORT": "8081",
    "L2_HOTEL_PORT": "8082",
    "L2_FLIGHT_PORT": "8086",
    "AGENT_B_HOTEL_PORT": "18191",
    "AGENT_B_FLIGHT_PORT": "18192",
}

HOTEL_PROMPT = (
    '你是酒店预订 Agent。收到订酒店请求时，按顺序调用 ask_user 依次询问：① 想定什么地方（目的地）；'
    '② 订哪天（入住日期）；③ 住几天。每收到一次用户回答就继续下一个问题，三个问题都问完后返回最终答案，'
    '内容包含"酒店预订成功"。不要跳过 ask_user。'
)
FLIGHT_PROMPT = (
    '你是机票预订 Agent。收到买机票请求时，先调用 ask_user 询问去哪里（目的地），恢复后返回最终答案，'
    '内容包含"机票预订成功"。'
)

# Scenarios mirror scripts/local-e2e-llm-intent.sh (single conversation_id):
#   A: 订酒店 → 上海（Agent B hotel ask_user：定什么地方 → 订哪天）
#   B: 买机票（L1 据历史识别话题切换 → L2_flight，ask_user：去哪里）
#   C: 继续订酒店（Agent B hotel shadow-task 恢复，ask_user：住几天）
SCENARIOS = {
    "a": ["订酒店", "上海"],
    "b": ["买机票"],
    "c": ["继续订酒店"],
}


def http_get_json(url):
    """GET a JSON document, returning the parsed object."""
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_post(url, body, stream=False):
    """POST a JSON body. Returns (status, raw_bytes-or-iterable-of-sse-data)."""
    data = json.dumps(body).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream" if stream else "application/json",
        "X-Biz-Tag": BIZ_TAG,
    }
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        resp = urllib.request.urlopen(req, timeout=120 if stream else 90)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    if stream:
        return resp.status, _iter_sse(resp)
    return resp.status, resp.read()


def _iter_sse(resp):
    """Yield decoded `data:` payloads from a Server-Sent-Events stream."""
    for raw in resp:
        line = raw.decode("utf-8", errors="replace").rstrip("\n")
        if line.startswith("data:"):
            yield line[len("data:"):].strip()


def get_agent_card(base_url):
    """Fetch and return the public agent card from `base_url`."""
    return http_get_json(base_url.rstrip("/") + AGENT_CARD_PATH)


def show_agent_card(base_url):
    """Pretty-print the public agent card (cf. test_client.get_agent_card)."""
    card = get_agent_card(base_url)
    print("--- Public Agent Card ---")
    print(json.dumps(card, ensure_ascii=False, indent=2))


def build_query_body(text, conversation_id, user_id, stream):
    """Build the POST /v1/query body, matching the shell script's send_q."""
    return {
        "conversation_id": conversation_id,
        "stream": stream,
        "user_id": user_id,
        "messages": [{"role": "user", "content": text}],
    }


def send_message(base_url, text, conversation_id, user_id, stream=False):
    """Send one user turn to L1 /v1/query and return the parsed response.

    For non-streaming calls the full JSON envelope is returned. For streaming
    calls a list of decoded SSE data payloads is returned.
    """
    body = build_query_body(text, conversation_id, user_id, stream)
    status, payload = http_post(base_url.rstrip("/") + QUERY_PATH, body, stream=stream)
    if status != 200:
        raise RuntimeError(f"POST /v1/query failed: HTTP {status}: {_truncate(payload)}")
    if stream:
        chunks = []
        for data in payload:
            chunks.append(data)
            print(f"  event: {data}")
        return chunks
    return json.loads(payload.decode("utf-8"))


def extract_text(result):
    """Best-effort extraction of a human-readable string from `result`."""
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    if isinstance(result, dict):
        for key in ("content", "text", "message", "response_content"):
            value = result.get(key)
            if isinstance(value, str) and value:
                return value
        interrupt = result.get("_interrupt")
        if isinstance(interrupt, dict):
            msg = interrupt.get("message")
            if isinstance(msg, str) and msg:
                return f"[需要输入] {msg}"
        return json.dumps(result, ensure_ascii=False)
    return json.dumps(result, ensure_ascii=False)


def _truncate(data, limit=600):
    text = data.decode("utf-8", errors="replace") if isinstance(data, (bytes, bytearray)) else str(data)
    return text if len(text) <= limit else text[:limit] + "..."


def print_response(response):
    """Pretty-print a non-streaming query response."""
    if isinstance(response, list):
        return
    conversation_id = response.get("conversation_id", "?")
    text = extract_text(response.get("result"))
    print(f"  conversation_id={conversation_id}")
    print(f"  assistant: {text}")


def run_scenario(base_url, which, conversation_id, user_id, stream=False):
    """Replay one (or all) scripted scenario(s) against a running L1."""
    order = ["a", "b", "c"] if which == "all" else [which]
    for code in order:
        turns = SCENARIOS[code]
        print(f"\n==== 场景 {code.upper()}: {' -> '.join(turns)} ====")
        for turn in turns:
            print(f"user > {turn}")
            response = send_message(base_url, turn, conversation_id, user_id, stream=stream)
            if not stream:
                print_response(response)


def chat(base_url, conversation_id, user_id, stream=False):
    """Interactive loop, mirroring test_client.main()."""
    print(f"\n交互式会话 L1={base_url} conversation_id={conversation_id} stream={stream}")
    print("输入消息回车发送，`exit` 退出，`card` 显示 agent card，`reset` 清空会话。")
    while True:
        try:
            prompt = input("user > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not prompt:
            continue
        if prompt == "exit":
            break
        if prompt == "card":
            try:
                show_agent_card(base_url)
            except Exception as exc:  # noqa: BLE001
                print(f"  获取 agent card 失败: {exc}")
            continue
        if prompt == "reset":
            conversation_id = f"c-cli-{_short_id()}"
            print(f"  已切换 conversation_id={conversation_id}")
            continue
        try:
            response = send_message(base_url, prompt, conversation_id, user_id, stream=stream)
            if not stream:
                print_response(response)
        except Exception as exc:  # noqa: BLE001
            print(f"  发送失败: {exc}")


def _short_id():
    """A short pseudo-id for new conversation ids (no external deps)."""
    import time
    return f"{int(time.time() * 1000) % 100000:05d}"


# --------------------------------------------------------------------------- #
# .env loading
# --------------------------------------------------------------------------- #

def load_dotenv(extra=None):
    """Populate os.environ from a .env file. Real env vars win (no override).

    Search order: an explicit `extra` path, then `<module>/.env`, then
    `./.env`. Returns the path that was applied, or None.
    """
    candidates = [extra, os.path.join(MODULE_DIR, ".env"), os.path.join(os.getcwd(), ".env")]
    for path in candidates:
        if path and os.path.isfile(path):
            _apply_dotenv(path)
            return path
    return None


def _apply_dotenv(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key and key not in os.environ:
                os.environ[key] = value


# --------------------------------------------------------------------------- #
# Stack management (the `start` subcommand)
# --------------------------------------------------------------------------- #

def _port(name):
    return int(os.environ.get(name, PORT_DEFAULTS[name]))


def _require_llm_env():
    """Validate that the LLM env vars needed to launch the stack are present."""
    missing = [k for k in ("LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL") if not os.environ.get(k)]
    if missing:
        raise SystemExit(
            f"ERROR: 缺少 LLM 环境变量 {', '.join(missing)}。\n"
            f"请复制 .env.example 为 .env 并填入真实值（{MODULE_DIR}/.env），\n"
            "或通过环境变量导出。密钥仅经环境变量/.env 传入，绝不提交到代码。")


def _build_if_needed():
    """Run `mvn package` for any missing jar (unless SKIP_BUILD=1)."""
    if os.environ.get("SKIP_BUILD") == "1":
        return
    for jar, mdir in [(JAR_FILE, MODULE_DIR), (AGENT_B_JAR, AGENT_B_MODULE)]:
        if not os.path.isfile(jar):
            print(f"==> 构建 {os.path.basename(jar)} ...")
            try:
                subprocess.run(["mvn", "-q", "package", "-DskipTests"], cwd=mdir, check=True)
            except FileNotFoundError:
                raise SystemExit("ERROR: 未找到 mvn，请先安装 Maven 并加入 PATH。")
            except subprocess.CalledProcessError as exc:
                raise SystemExit(f"ERROR: 构建失败 ({mdir}): {exc}")


def _start_proc(jar, args, logname):
    """Launch `java -jar <jar> <args>` in its own process group, log to target/."""
    os.makedirs(LOG_DIR, exist_ok=True)
    log = open(os.path.join(LOG_DIR, f"{logname}.log"), "ab", buffering=0)
    proc = subprocess.Popen(
        ["java", "-jar", jar, *args],
        cwd=MODULE_DIR, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    return proc


def _gateway_args(gw, l2f, abh, abf):
    r = "routing.agent_card_"
    return [
        "--spring.profiles.active=mock-a2a-gateway",
        f"--server.port={gw}",
        f"--openjiuwen.service.versatile.url-template=http://localhost:{gw}/v1/proj/agents/agent_mock_gateway/conversations/{{conversation_id}}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}L2_flight=http://localhost:{l2f}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}L2_flight_a=http://localhost:{l2f}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}L2_flight_b=http://localhost:{l2f}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}biz_hotel_domestic=http://localhost:{abh}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}biz_hotel_international=http://localhost:{abh}",
        f"--openjiuwen.example.mock-a2a-gateway.{r}biz_flight_domestic=http://localhost:{abf}",
        "--openjiuwen.example.mock-a2a-gateway.passthrough-cards=agent_card_biz_hotel_domestic,agent_card_biz_hotel_international,agent_card_biz_flight_domestic",
    ]


def _versatile_args(profiles, port, seg, extra):
    return [
        f"--spring.profiles.active={profiles}",
        f"--server.port={port}",
        f"--openjiuwen.service.versatile.url-template=http://localhost:{port}/v1/proj/agents/{seg}/conversations/{{conversation_id}}",
        *extra,
    ]


def _agent_b_args(port, workspace, prompt):
    return [
        f"--server.port={port}",
        f"--openjiuwen.demo.deep-agent.llm.workspace-path={workspace}",
        f"--openjiuwen.demo.deep-agent.llm.system-prompt={prompt}",
    ]


def _reachable(port):
    for path in ("/.well-known/agent-card.json", "/actuator/health"):
        try:
            req = urllib.request.Request(f"http://localhost:{port}{path}")
            with urllib.request.urlopen(req, timeout=2) as resp:
                if 200 <= resp.status < 500:
                    return True
        except Exception:  # noqa: BLE001
            continue
    return False


def _wait_health(port, name, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _reachable(port):
            print(f"    {name:<14} UP (port {port})")
            return True
        time.sleep(1)
    print(f"    {name:<14} TIMEOUT (port {port})", file=sys.stderr)
    tail = os.path.join(LOG_DIR, f"{name}.log")
    if os.path.isfile(tail):
        with open(tail, encoding="utf-8", errors="replace") as fh:
            print("".join(fh.readlines()[-30:]), file=sys.stderr)
    return False


def start_stack():
    """Launch the 6-process demo stack. Returns (procs, base_url) or raises."""
    _require_llm_env()
    # Agent B (DEEPSEEK_*) defaults to the LLM_* config when unset.
    os.environ.setdefault("DEEPSEEK_API_KEY", os.environ["LLM_API_KEY"])
    os.environ.setdefault("DEEPSEEK_BASE_URL", os.environ["LLM_BASE_URL"])
    os.environ.setdefault("DEEPSEEK_MODEL", os.environ["LLM_MODEL"])

    _build_if_needed()
    gw, l1, l2h, l2f = _port("GATEWAY_PORT"), _port("L1_PORT"), _port("L2_HOTEL_PORT"), _port("L2_FLIGHT_PORT")
    abh, abf = _port("AGENT_B_HOTEL_PORT"), _port("AGENT_B_FLIGHT_PORT")

    procs = []
    procs.append(("gateway", _start_proc(JAR_FILE, _gateway_args(gw, l2f, abh, abf), "gateway")))
    procs.append(("layer1", _start_proc(JAR_FILE,
        _versatile_args("layer1,dev,mock-versatile,a2a-gateway-test,llm-intent", l1, "agent_L1",
                        ["--openjiuwen.service.versatile.route-cache.enabled=false"]), "layer1")))
    procs.append(("layer2-hotel", _start_proc(JAR_FILE,
        _versatile_args("layer2,dev,mock-versatile,a2a-gateway-test,llm-intent", l2h, "agent_L2",
                        ["--openjiuwen.service.versatile.default-workflow.agent-card=",
                         "--openjiuwen.example.intent-llm.domain=hotel"]), "layer2-hotel")))
    procs.append(("layer2-flight", _start_proc(JAR_FILE,
        _versatile_args("layer2-flight,dev,mock-versatile,a2a-gateway-test,llm-intent", l2f, "agent_L2_flight",
                        ["--openjiuwen.service.versatile.default-workflow.agent-card=",
                         "--openjiuwen.example.intent-llm.domain=flight"]), "layer2-flight")))
    procs.append(("agent-b-hotel", _start_proc(AGENT_B_JAR,
        _agent_b_args(abh, "target/agent-b-hotel", HOTEL_PROMPT), "agent-b-hotel")))
    procs.append(("agent-b-flight", _start_proc(AGENT_B_JAR,
        _agent_b_args(abf, "target/agent-b-flight", FLIGHT_PROMPT), "agent-b-flight")))

    print("==> 等待进程就绪 ...")
    for name, port in [("gateway", gw), ("layer1", l1), ("layer2-hotel", l2h),
                       ("layer2-flight", l2f), ("agent-b-hotel", abh), ("agent-b-flight", abf)]:
        if not _wait_health(port, name):
            cleanup(procs)
            raise SystemExit(f"ERROR: {name} 启动失败 (port {port})")
    return procs, f"http://localhost:{l1}"


def cleanup(procs):
    """Terminate every launched process group (idempotent)."""
    for name, proc in reversed(procs):
        if proc.poll() is not None:
            continue
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        except ProcessLookupError:
            pass
        except OSError:
            proc.terminate()
    for _, proc in procs:
        try:
            proc.wait(timeout=10)
        except Exception:  # noqa: BLE001
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except OSError:
                pass


def cmd_start(args):
    """Launch the stack, then run a scenario or chat, then tear down."""
    procs, base_url = start_stack()
    atexit.register(cleanup, procs)
    print(f"\n==> 演示栈就绪，L1={base_url}  日志: {LOG_DIR}/{{gateway,layer1,layer2-hotel,layer2-flight,agent-b-hotel,agent-b-flight}}.log")
    print("==> Ctrl+C 退出并清理进程。")
    try:
        if args.scenario:
            run_scenario(base_url, args.scenario, args.conversation_id, args.user_id, stream=args.stream)
        else:
            chat(base_url, args.conversation_id, args.user_id, stream=args.stream)
    except KeyboardInterrupt:
        print("\n==> 中断，清理进程 ...")
    finally:
        cleanup(procs)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="CLI client for the versatile-intent-boot LLM intent demo.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"L1 base URL (default {DEFAULT_BASE_URL})")
    parser.add_argument("--conversation-id", default=DEFAULT_CONVERSATION_ID,
                        help=f"conversation id shared across turns (default {DEFAULT_CONVERSATION_ID})")
    parser.add_argument("--user-id", default=DEFAULT_USER_ID, help=f"user id (default {DEFAULT_USER_ID})")
    parser.add_argument("--stream", action="store_true", help="request streaming SSE responses")
    parser.add_argument("--env-file", default=None, help="extra .env file to load (default <module>/.env)")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("card", help="fetch and display the L1 agent card")
    scenario_parser = sub.add_parser("scenario", help="replay a scripted scenario (a | b | c | all)")
    scenario_parser.add_argument("which", choices=["a", "b", "c", "all"], help="which scenario to run")
    sub.add_parser("chat", help="interactive chat loop (default)")
    start_parser = sub.add_parser("start", help="launch the demo stack (reads .env), then chat or run a scenario")
    start_parser.add_argument("--scenario", choices=["a", "b", "c", "all"],
                              help="run a scenario after startup, then exit (default: interactive chat)")
    start_parser.add_argument("--no-build", action="store_true", help="skip `mvn package` (reuse existing jars)")
    args = parser.parse_args(argv)

    # Load .env so `start` gets LLM_* params and port overrides; harmless for
    # the client commands. Real env vars keep precedence.
    env_path = load_dotenv(args.env_file)
    if args.command == "start" and env_path:
        print(f"==> 已加载 .env: {env_path}")
    if args.command == "start" and args.no_build:
        os.environ["SKIP_BUILD"] = "1"

    command = args.command or "chat"
    try:
        if command == "start":
            return cmd_start(args)
        if command == "card":
            show_agent_card(args.base_url)
        elif command == "scenario":
            run_scenario(args.base_url, args.which, args.conversation_id, args.user_id, stream=args.stream)
        elif command == "chat":
            chat(args.base_url, args.conversation_id, args.user_id, stream=args.stream)
    except urllib.error.URLError as exc:
        print(f"ERROR: 无法连接 L1 ({args.base_url}): {exc.reason}", file=sys.stderr)
        print("请先启动演示进程栈：python3 scripts/cli-llm-intent.py start", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
