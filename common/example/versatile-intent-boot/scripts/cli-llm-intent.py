#!/usr/bin/env python3
#
# CLI client for the LLM intent demo (versatile-intent-boot).
#
# Mirrors the shape of a2a-samples' helloworld/test_client.py: fetch & display
# the agent card, send streaming / non-streaming messages, replay the scripted
# scenarios from local-e2e-llm-intent.sh, and drop into an interactive chat
# loop.
#
# Stdlib-only — no httpx / a2a-sdk dependency, runs with plain `python3`.
#
# Prerequisite: the demo stack must already be up (gateway + L1 + L2_hotel +
# L2_flight + Agent B hotel + Agent B flight). `local-e2e-llm-intent.sh` is the
# reference for the exact `java -jar` start commands; it runs its own scenarios
# and tears the stack down on exit, so to drive the stack with this CLI, start
# those six processes and keep them running, then point this CLI at L1
# (default http://localhost:8081). Use `python3 ... card` to confirm L1 is up.
#
# Usage:
#     python3 scripts/cli-llm-intent.py card                       # show L1 agent card
#     python3 scripts/cli-llm-intent.py scenario a                 # replay scenario A
#     python3 scripts/cli-llm-intent.py scenario all              # replay A -> B -> C
#     python3 scripts/cli-llm-intent.py chat                       # interactive loop (non-streaming)
#     python3 scripts/cli-llm-intent.py chat --stream              # interactive loop (SSE streaming)
#     python3 scripts/cli-llm-intent.py --base-url http://host:8081 chat
#
# Options:
#     --base-url URL         L1 base URL (default http://localhost:8081)
#     --conversation-id ID   conversation id shared across turns (default c-llm-demo)
#     --user-id ID           user id sent in the query body (default u-42)
#     --stream               request streaming SSE responses (chat / scenario)
#     -h, --help             show this help

import argparse
import json
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://localhost:8081"
DEFAULT_CONVERSATION_ID = "c-llm-demo"
DEFAULT_USER_ID = "u-42"
AGENT_CARD_PATH = "/.well-known/agent-card.json"
QUERY_PATH = "/v1/query"
BIZ_TAG = "llm-demo"

# Scenarios mirror scripts/local-e2e-llm-intent.sh (single conversation_id):
#   A: 订酒店多轮 ask-user（Agent B hotel 追问预算/星级）
#   B: 跳转买机票（L1 据历史识别话题切换 → L2_flight）
#   C: 回跳完成酒店（Agent B hotel shadow-task 续传出单）
SCENARIOS = {
    "a": ["订酒店", "500元"],
    "b": ["买机票"],
    "c": ["继续订酒店", "五星"],
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
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("card", help="fetch and display the L1 agent card")
    scenario_parser = sub.add_parser("scenario", help="replay a scripted scenario (a | b | c | all)")
    scenario_parser.add_argument("which", choices=["a", "b", "c", "all"], help="which scenario to run")
    sub.add_parser("chat", help="interactive chat loop (default)")
    args = parser.parse_args(argv)

    command = args.command or "chat"
    try:
        if command == "card":
            show_agent_card(args.base_url)
        elif command == "scenario":
            run_scenario(args.base_url, args.which, args.conversation_id, args.user_id, stream=args.stream)
        elif command == "chat":
            chat(args.base_url, args.conversation_id, args.user_id, stream=args.stream)
    except urllib.error.URLError as exc:
        print(f"ERROR: 无法连接 L1 ({args.base_url}): {exc.reason}", file=sys.stderr)
        print("请先启动演示进程栈：./scripts/local-e2e-llm-intent.sh", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
