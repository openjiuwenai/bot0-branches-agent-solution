#!/usr/bin/env python3
"""命令行与 EDPAgent 对话（直连 SSE 或经 Adapter 代理）。

默认对接本地服务（可用环境变量覆盖）：
  EDPAgent  http://127.0.0.1:18001
  Adapter   http://127.0.0.1:8900

用法示例：
  # 交互式（直连 EDPAgent）
  python scripts/chat_edpagent.py

  # 单轮提问
  python scripts/chat_edpagent.py -q "你好，请用一句话介绍你自己。"

  # 经 Adapter 代理（便于联调轨迹/热更）
  python scripts/chat_edpagent.py --via-adapter -q "你好"

  # 打印流式事件
  python scripts/chat_edpagent.py -v -q "你好"

环境变量（可选，覆盖默认）：
  EDP_URL / ADAPTER_URL / EDP_PROJECT_ID / EDP_AGENT_ID / ADAPTER_AGENT_NAME
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from dataclasses import dataclass, field
from typing import Any
from urllib import error as urlerror
from urllib import request as urlrequest


DEFAULT_EDP_URL = "http://127.0.0.1:18001"
DEFAULT_ADAPTER_URL = "http://127.0.0.1:8900"
DEFAULT_PROJECT_ID = "proj_001"
DEFAULT_AGENT_ID = "edp_agent"


@dataclass
class ChatResult:
    success: bool
    conversation_id: str
    answer: str = ""
    error: str | None = None
    interrupted: bool = False
    events: list[dict[str, Any]] = field(default_factory=list)


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default).strip() or default


def health_check(base: str, timeout: float = 8.0) -> tuple[bool, str]:
    url = f"{base.rstrip('/')}/health"
    req = urlrequest.Request(url, method="GET")
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return True, raw.strip() or f"HTTP {resp.status}"
    except Exception as exc:  # noqa: BLE001 — CLI 探测统一返回
        return False, str(exc)


def _parse_sse_frame(line: str) -> dict[str, Any] | None:
    line = line.strip()
    if not line or not line.startswith("data: "):
        return None
    data_str = line[len("data: ") :]
    if data_str.strip() == "[DONE]":
        return None
    try:
        parsed = json.loads(data_str)
    except (json.JSONDecodeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def _assemble_from_sse(
    conversation_id: str,
    frames: list[dict[str, Any]],
) -> ChatResult:
    answer_parts: list[str] = []
    final_chunk: str | None = None
    interrupted = False
    events: list[dict[str, Any]] = []

    for raw in frames:
        # 少数实现直接把 error 放在 data 根上
        if raw.get("success") is False and raw.get("error"):
            return ChatResult(
                success=False,
                conversation_id=conversation_id,
                error=str(raw["error"]),
                events=events,
            )

        custom = raw.get("custom_rsp_data")
        if not isinstance(custom, dict):
            continue
        event_type = str(custom.get("event") or "")
        content = custom.get("content", "")
        content_s = content if isinstance(content, str) else str(content)
        events.append({"type": event_type, "content": content_s, "plugin": custom.get("plugin")})

        if event_type == "summary":
            answer_parts.append(content_s)
        elif event_type == "final_answer_chunk":
            final_chunk = content_s
        elif event_type == "interrupt_start":
            interrupted = True

    answer = final_chunk if final_chunk is not None else "".join(answer_parts)
    return ChatResult(
        success=True,
        conversation_id=conversation_id,
        answer=answer,
        interrupted=interrupted,
        events=events,
    )


def chat_direct(
    *,
    edp_url: str,
    project_id: str,
    agent_id: str,
    conversation_id: str,
    query: str,
    timeout: float,
    verbose: bool,
) -> ChatResult:
    url = (
        f"{edp_url.rstrip('/')}/v1/{project_id}"
        f"/agents/{agent_id}/conversations/{conversation_id}"
    )
    body = {
        "agent_id": agent_id,
        "conversation_id": conversation_id,
        "stream": True,
        "input": {"query": query},
        "custom_data": {"inputs": {"query": query}},
    }
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urlrequest.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )

    frames: list[dict[str, Any]] = []
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            # 按行读 SSE
            while True:
                raw_line = resp.readline()
                if not raw_line:
                    break
                line = raw_line.decode("utf-8", errors="replace")
                frame = _parse_sse_frame(line)
                if frame is None:
                    continue
                frames.append(frame)
                if verbose:
                    custom = frame.get("custom_rsp_data") or {}
                    et = custom.get("event") if isinstance(custom, dict) else None
                    content = custom.get("content") if isinstance(custom, dict) else None
                    preview = ""
                    if isinstance(content, str) and content:
                        preview = content.replace("\n", " ")[:120]
                    print(f"  [sse] {et or '?'} {preview}", file=sys.stderr)
    except urlerror.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return ChatResult(
            success=False,
            conversation_id=conversation_id,
            error=f"HTTP {exc.code}: {raw[:800]}",
        )
    except Exception as exc:  # noqa: BLE001
        return ChatResult(success=False, conversation_id=conversation_id, error=str(exc))

    return _assemble_from_sse(conversation_id, frames)


def chat_via_adapter(
    *,
    adapter_url: str,
    agent_name: str,
    conversation_id: str,
    query: str,
    timeout: float,
) -> ChatResult:
    url = (
        f"{adapter_url.rstrip('/')}/api/v1/agents/"
        f"{agent_name}/conversations/{conversation_id}"
    )
    data = json.dumps({"query": query}, ensure_ascii=False).encode("utf-8")
    req = urlrequest.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            payload = json.loads(raw) if raw.strip() else {}
    except urlerror.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return ChatResult(
            success=False,
            conversation_id=conversation_id,
            error=f"HTTP {exc.code}: {raw[:800]}",
        )
    except Exception as exc:  # noqa: BLE001
        return ChatResult(success=False, conversation_id=conversation_id, error=str(exc))

    answer = ""
    if isinstance(payload.get("answer"), str):
        answer = payload["answer"]
    else:
        for ev in payload.get("events") or []:
            if isinstance(ev, dict) and ev.get("type") == "final_answer_chunk" and ev.get("content"):
                answer = str(ev["content"])
                break

    return ChatResult(
        success=bool(payload.get("success", False)),
        conversation_id=str(payload.get("conversation_id") or conversation_id),
        answer=answer,
        error=payload.get("error"),
        interrupted=bool(payload.get("interrupted", False)),
        events=list(payload.get("events") or []),
    )


def print_result(result: ChatResult, *, show_events: bool = False) -> None:
    print(f"conversation_id: {result.conversation_id}")
    if not result.success:
        print(f"FAIL: {result.error or 'unknown error'}")
        return
    # 部分场景会发 interrupt_start 但仍有最终答复；仅在无答案时强调中断
    if result.interrupted and not (result.answer or "").strip():
        print("(interrupted, waiting for user / no final answer)")
    print("--- answer ---")
    print(result.answer or "(empty)")
    if show_events and result.events:
        print("--- events ---")
        print(json.dumps(result.events, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="命令行与远程/本地 EDPAgent 对话（直连 SSE 或经 Adapter）",
    )
    p.add_argument(
        "--edp-url",
        default=_env("EDP_URL", DEFAULT_EDP_URL),
        help=f"EDPAgent base URL (default: {DEFAULT_EDP_URL})",
    )
    p.add_argument(
        "--adapter-url",
        default=_env("ADAPTER_URL", DEFAULT_ADAPTER_URL),
        help=f"Adapter base URL (default: {DEFAULT_ADAPTER_URL})",
    )
    p.add_argument(
        "--via-adapter",
        action="store_true",
        help="经 Adapter 调用（聚合 JSON）；默认直连 EDPAgent SSE",
    )
    p.add_argument(
        "--project-id",
        default=_env("EDP_PROJECT_ID", DEFAULT_PROJECT_ID),
        help="直连路径中的 project_id",
    )
    p.add_argument(
        "--agent-id",
        default=_env("EDP_AGENT_ID", DEFAULT_AGENT_ID),
        help="直连路径中的 agent_id",
    )
    p.add_argument(
        "--agent-name",
        default=_env("ADAPTER_AGENT_NAME", DEFAULT_AGENT_ID),
        help="Adapter 路径中的 agent_name",
    )
    p.add_argument(
        "--conversation-id",
        default="",
        help="会话 ID；省略则自动生成 manual-<hex>",
    )
    p.add_argument("-q", "--query", default="", help="单轮提问；省略则进入交互模式")
    p.add_argument("--timeout", type=float, default=300.0, help="单轮超时秒数")
    p.add_argument("-v", "--verbose", action="store_true", help="打印 SSE 事件预览")
    p.add_argument("--show-events", action="store_true", help="结束后打印事件列表 JSON")
    p.add_argument("--skip-health", action="store_true", help="跳过启动前 /health 检查")
    return p


def one_turn(args: argparse.Namespace, conversation_id: str, query: str) -> ChatResult:
    if args.via_adapter:
        return chat_via_adapter(
            adapter_url=args.adapter_url,
            agent_name=args.agent_name,
            conversation_id=conversation_id,
            query=query,
            timeout=args.timeout,
        )
    return chat_direct(
        edp_url=args.edp_url,
        project_id=args.project_id,
        agent_id=args.agent_id,
        conversation_id=conversation_id,
        query=query,
        timeout=args.timeout,
        verbose=args.verbose,
    )


def _configure_stdio() -> None:
    """尽量让 Windows 控制台按 UTF-8 打印中文。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def main(argv: list[str] | None = None) -> int:
    _configure_stdio()
    args = build_parser().parse_args(argv)
    conversation_id = args.conversation_id.strip() or f"manual-{uuid.uuid4().hex[:12]}"

    if not args.skip_health:
        target = args.adapter_url if args.via_adapter else args.edp_url
        ok, detail = health_check(target)
        mode = "adapter" if args.via_adapter else "edp"
        print(f"[health:{mode}] {'OK' if ok else 'FAIL'} {target} -> {detail}")
        if not ok:
            return 2

    if args.query.strip():
        print(f"[ask] {args.query}")
        result = one_turn(args, conversation_id, args.query.strip())
        print_result(result, show_events=args.show_events)
        return 0 if result.success else 1

    mode = "via-adapter" if args.via_adapter else "direct-sse"
    print(f"交互模式（{mode}），会话 {conversation_id}")
    print("输入消息回车发送；空行 /exit /quit 退出；/new 开新会话")
    while True:
        try:
            line = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line or line in {"/exit", "/quit", "exit", "quit"}:
            break
        if line == "/new":
            conversation_id = f"manual-{uuid.uuid4().hex[:12]}"
            print(f"[new conversation] {conversation_id}")
            continue
        result = one_turn(args, conversation_id, line)
        print_result(result, show_events=args.show_events)
        if not result.success:
            print("(本轮失败，可继续输入重试)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
