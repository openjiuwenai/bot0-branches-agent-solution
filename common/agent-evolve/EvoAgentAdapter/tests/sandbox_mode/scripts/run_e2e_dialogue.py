"""Sandbox-mode E2E scenario (TC-E2E; log steps TC-15 ~ TC-19).

Business path: seed MARKER_A → dialogue A → hot-update to MARKER_B →
dialogue B (same prompt, no marker leak) → traces for both conversations.

Level notes (industry):
  - TC-15: E2E setup (data prep), not a standalone E2E gate
  - TC-16 / TC-18 / TC-19: E2E gates
  - TC-17: integration (sandbox FS write verify), run inside this scenario

Usage:
    python run_e2e_dialogue.py
    python run_e2e_dialogue.py --adapter-url http://127.0.0.1:18900
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from config import SandboxModeConfig, parse_sandbox_args
else:
    from .config import SandboxModeConfig, parse_sandbox_args

REPORT_DIR = Path(__file__).resolve().parents[1] / "reports"


@dataclass
class Result:
    case_id: str
    name: str
    passed: bool
    detail: str = ""
    duration_ms: int = 0
    evidence: dict = field(default_factory=dict)


def api(
    base: str,
    method: str,
    path: str,
    body: dict | None = None,
    *,
    timeout: int = 300,
) -> tuple[int, Any]:
    url = f"{base.rstrip('/')}{path}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"} if data else {},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return e.code, payload
    except urllib.error.URLError as e:
        return 0, {"error": str(e)}


def skill_action(cfg: SandboxModeConfig, action: str, **extra: Any) -> tuple[int, Any]:
    return api(
        cfg.adapter_url_normalized,
        "POST",
        "/api/v1/skills",
        {"agent_name": cfg.agent_name, "action": action, **extra},
    )


def resolve_sandbox_id(cfg: SandboxModeConfig) -> str | None:
    code, data = api(cfg.jiuwenbox_url_normalized, "GET", "/api/v1/sandboxes", timeout=30)
    if code != 200:
        return None
    items = data if isinstance(data, list) else data.get("items") or []
    ready = [
        s
        for s in items
        if isinstance(s, dict) and str(s.get("phase", "")).lower() == "ready"
    ]
    if not ready:
        return None
    return str(ready[-1].get("id"))


def jb_download(cfg: SandboxModeConfig, sandbox_id: str, path: str) -> str:
    url = (
        f"{cfg.jiuwenbox_url_normalized}/api/v1/sandboxes/"
        f"{urllib.parse.quote(sandbox_id)}/download"
        f"?{urllib.parse.urlencode({'sandbox_path': path})}"
    )
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")


def jb_upload(cfg: SandboxModeConfig, sandbox_id: str, path: str, content: str) -> int:
    """Raw multipart upload when skill does not yet exist (update contract requires exist)."""
    boundary = f"----Boundary{uuid.uuid4().hex}"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="SKILL.md"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
        f"{content}\r\n"
        f"--{boundary}--\r\n"
    ).encode("utf-8")
    url = (
        f"{cfg.jiuwenbox_url_normalized}/api/v1/sandboxes/"
        f"{urllib.parse.quote(sandbox_id)}/upload"
        f"?{urllib.parse.urlencode({'sandbox_path': path})}"
    )
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def make_skill_doc(skill: str, marker: str) -> str:
    return f"""---
name: {skill}
description: E2E sandbox hotupdate probe skill. Contains HOTUPDATE_MARKER for verification.
---

# Product Recommend Skill (E2E Probe)

HOTUPDATE_MARKER={marker}

When asked to verify skill content, use read_file on this file and quote the HOTUPDATE_MARKER line exactly.
"""


def ask_quote_marker(remote_path: str) -> str:
    # Intentionally NO marker value in the prompt (anti-cheat).
    return (
        "这是联调探针，不要做理财推荐。"
        f"请立刻用 read_file 工具读取文件 `{remote_path}`，"
        "在最终回答里原样输出文件中以 `HOTUPDATE_MARKER=` 开头的那一行（整行复制，不要改写）。"
        "不要调用 call_mcp/call_versatile/ask_user。"
    )


def extract_answer(resp: dict) -> str:
    if resp.get("answer"):
        return str(resp["answer"])
    for ev in resp.get("events") or []:
        if ev.get("type") == "final_answer_chunk" and ev.get("content"):
            return str(ev["content"])
    return ""


def get_traces(cfg: SandboxModeConfig, conv_id: str) -> dict:
    api(cfg.adapter_url_normalized, "GET", f"/api/v1/agents/{cfg.agent_name}/traces", timeout=30)
    code, data = api(
        cfg.adapter_url_normalized,
        "GET",
        f"/api/v1/agents/{cfg.agent_name}/traces/{conv_id}",
        timeout=60,
    )
    if isinstance(data, dict):
        data["_http"] = code
    return data if isinstance(data, dict) else {"_http": code, "raw": data}


def run_all(cfg: SandboxModeConfig) -> tuple[list[Result], dict]:
    results: list[Result] = []
    skill = cfg.skill_name
    remote = cfg.remote_skill_md
    marker_a = f"BEFORE_{uuid.uuid4().hex[:8].upper()}"
    marker_b = f"AFTER_{uuid.uuid4().hex[:8].upper()}"
    query = ask_quote_marker(remote)

    sandbox_id = resolve_sandbox_id(cfg)
    meta = {
        "sandbox_id": sandbox_id,
        "marker_a": marker_a,
        "marker_b": marker_b,
        "user_query": query,
        "skill": skill,
        "remote_path": remote,
    }

    # TC-15 seed MARKER_A
    t0 = time.perf_counter()
    doc_a = make_skill_doc(skill, marker_a)
    seeded = False
    if sandbox_id:
        try:
            jb_download(cfg, sandbox_id, remote)
            exists = True
        except Exception:
            exists = False
        if not exists:
            status = jb_upload(cfg, sandbox_id, remote, doc_a)
            seeded = status in (200, 204)
        if not seeded:
            code, upd = skill_action(
                cfg,
                "update_skill",
                skill_name=skill,
                skill_content=doc_a,
            )
            seeded = code == 200 and upd.get("success") is True
            if not seeded and sandbox_id:
                status = jb_upload(cfg, sandbox_id, remote, doc_a)
                seeded = status in (200, 204)
                if seeded:
                    # Also push via adapter so snapshot/meta stays consistent when possible
                    skill_action(cfg, "update_skill", skill_name=skill, skill_content=doc_a)

    on_disk = ""
    if sandbox_id:
        try:
            on_disk = jb_download(cfg, sandbox_id, remote)
        except Exception as exc:
            on_disk = str(exc)
    ok = seeded and marker_a in on_disk
    results.append(
        Result(
            "TC-15",
            "[准备] 写入 Skill MARKER_A（沙箱真源）",
            ok,
            f"seeded={seeded}; marker_in_sandbox={marker_a in on_disk}",
            int((time.perf_counter() - t0) * 1000),
            {"sandbox_id": sandbox_id, "marker_a": marker_a},
        )
    )

    # TC-16 dialogue A
    t0 = time.perf_counter()
    conv_a = f"sbx-dlg-a-{uuid.uuid4().hex[:10]}"
    code, resp_a = api(
        cfg.adapter_url_normalized,
        "POST",
        f"/api/v1/agents/{cfg.agent_name}/conversations/{conv_a}",
        {"query": query},
        timeout=300,
    )
    answer_a = extract_answer(resp_a)
    saw_a = marker_a in answer_a
    saw_b_in_a = marker_b in answer_a
    ok = code == 200 and resp_a.get("success") is True and saw_a and not saw_b_in_a
    results.append(
        Result(
            "TC-16",
            "[E2E] 对话A（无提示下读到 MARKER_A）",
            ok,
            f"success={resp_a.get('success')} saw_A={saw_a} answer={answer_a[:240]}",
            int((time.perf_counter() - t0) * 1000),
            {
                "conversation_id": conv_a,
                "user_query": query,
                "http": code,
                "success": resp_a.get("success"),
                "answer": answer_a[:800],
            },
        )
    )

    # TC-17 hot-update to B + dialogue B
    t0 = time.perf_counter()
    doc_b = make_skill_doc(skill, marker_b)
    code_u, upd = skill_action(cfg, "update_skill", skill_name=skill, skill_content=doc_b)
    on_disk_b = jb_download(cfg, sandbox_id, remote) if sandbox_id else ""
    hot_ok = (
        code_u == 200
        and upd.get("success") is True
        and marker_b in on_disk_b
        and marker_a not in on_disk_b
    )
    results.append(
        Result(
            "TC-17",
            "[集成] Skill 热更 API → 沙箱变为 MARKER_B",
            hot_ok,
            f"update_http={code_u} success={upd.get('success')} disk_has_B={marker_b in on_disk_b}",
            int((time.perf_counter() - t0) * 1000),
            {"sandbox_preview": on_disk_b[:400]},
        )
    )

    t0 = time.perf_counter()
    conv_b = f"sbx-dlg-b-{uuid.uuid4().hex[:10]}"
    code, resp_b = api(
        cfg.adapter_url_normalized,
        "POST",
        f"/api/v1/agents/{cfg.agent_name}/conversations/{conv_b}",
        {"query": query},
        timeout=300,
    )
    answer_b = extract_answer(resp_b)
    saw_b = marker_b in answer_b
    saw_a_in_b = marker_a in answer_b
    ok = code == 200 and resp_b.get("success") is True and saw_b and not saw_a_in_b
    results.append(
        Result(
            "TC-18",
            "[E2E] 对话B（无提示下读到 MARKER_B，证明热更生效）",
            ok,
            f"success={resp_b.get('success')} saw_B={saw_b} saw_old_A={saw_a_in_b} answer={answer_b[:240]}",
            int((time.perf_counter() - t0) * 1000),
            {
                "conversation_id": conv_b,
                "user_query": query,
                "http": code,
                "success": resp_b.get("success"),
                "answer": answer_b[:800],
                "marker_a": marker_a,
                "marker_b": marker_b,
            },
        )
    )

    # TC-19 traces for both
    t0 = time.perf_counter()
    time.sleep(2)
    tr_a = get_traces(cfg, conv_a)
    tr_b = get_traces(cfg, conv_b)
    total_a = tr_a.get("total")
    total_b = tr_b.get("total")
    if total_a is None:
        total_a = len(tr_a.get("calls") or [])
    if total_b is None:
        total_b = len(tr_b.get("calls") or [])
    ok = int(total_a or 0) > 0 and int(total_b or 0) > 0
    results.append(
        Result(
            "TC-19",
            "[E2E] 轨迹采集（对话A+B 均有 traces）",
            ok,
            f"A total={total_a} B total={total_b}",
            int((time.perf_counter() - t0) * 1000),
            {"conv_a": conv_a, "trace_a": total_a, "conv_b": conv_b, "trace_b": total_b},
        )
    )

    meta["conv_a"] = conv_a
    meta["conv_b"] = conv_b
    return results, meta


def main() -> int:
    cfg = parse_sandbox_args("Sandbox-mode E2E scenario TC-E2E (steps TC-15~19)")
    results, meta = run_all(cfg)
    passed = sum(1 for r in results if r.passed)
    print("=" * 70)
    print(
        f"沙箱模式 E2E 场景 | adapter={cfg.adapter_url_normalized} "
        f"| markers A={meta.get('marker_a')} B={meta.get('marker_b')}"
    )
    print("=" * 70)
    for r in results:
        status = "PASS" if r.passed else "FAIL"
        print(f"  [{status}] {r.case_id} {r.name} ({r.duration_ms} ms)")
        if r.detail:
            print(f"         {r.detail}")
    print("=" * 70)
    print(f"合计: {passed}/{len(results)} 通过")
    print(f"USER_QUERY={meta.get('user_query')}")

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = REPORT_DIR / f"e2e_dialogue_{stamp}.json"
    payload = {
        "meta": {
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "adapter_url": cfg.adapter_url_normalized,
            "edp_url": cfg.edp_url_normalized,
            "jiuwenbox_url": cfg.jiuwenbox_url_normalized,
            "agent_name": cfg.agent_name,
            "passed": passed,
            "total": len(results),
            **meta,
        },
        "results": [asdict(r) for r in results],
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"REPORT_JSON={out}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
