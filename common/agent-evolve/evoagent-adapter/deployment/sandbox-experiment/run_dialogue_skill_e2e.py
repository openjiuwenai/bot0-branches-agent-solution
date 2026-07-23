#!/usr/bin/env python3
"""Dialogue-level E2E: proxy + traces + skill hot-update proven by two conversations.

Flow:
  1) Seed skill with MARKER_A, ask agent to read_file and quote it
  2) Hot-update skill to MARKER_B via Adapter → jiuwenbox
  3) New conversation: ask agent again; answer must contain MARKER_B not MARKER_A
  4) Confirm both conversations produced traces
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import httpx

REPORT_DIR = Path(__file__).resolve().parent / "reports"
SKILL = "product_recommend_skill"
REMOTE = f"/tmp/skills/{SKILL}/SKILL.md"


@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str
    duration_ms: int = 0
    evidence: dict = field(default_factory=dict)


def client() -> httpx.Client:
    return httpx.Client(timeout=httpx.Timeout(300.0, connect=30.0), trust_env=False)


def invoke(adapter: str, agent: str, query: str, conv_id: str | None = None) -> tuple[str, dict]:
    conv_id = conv_id or f"dlg-{uuid.uuid4().hex[:12]}"
    url = f"{adapter.rstrip('/')}/api/v1/agents/{agent}/conversations/{conv_id}"
    with client() as c:
        r = c.post(url, json={"query": query})
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {"raw": r.text}
    return conv_id, {"http": r.status_code, **(body if isinstance(body, dict) else {"body": body})}


def skill_action(adapter: str, payload: dict) -> dict:
    with client() as c:
        r = c.post(f"{adapter.rstrip('/')}/api/v1/skills", json=payload)
    data = r.json() if r.status_code < 500 else {"raw": r.text}
    data["_http"] = r.status_code
    return data


def get_traces(adapter: str, agent: str, conv_id: str) -> dict:
    with client() as c:
        # list endpoint triggers poll
        c.get(f"{adapter.rstrip('/')}/api/v1/agents/{agent}/traces")
        r = c.get(f"{adapter.rstrip('/')}/api/v1/agents/{agent}/traces/{conv_id}")
    return {"http": r.status_code, **(r.json() if r.status_code == 200 else {"raw": r.text[:400]})}


def jb_download(jb: str, sandbox_id: str, path: str) -> str:
    with client() as c:
        r = c.get(
            f"{jb.rstrip('/')}/api/v1/sandboxes/{sandbox_id}/download",
            params={"sandbox_path": path},
        )
    if r.status_code != 200:
        raise RuntimeError(f"download {path} HTTP {r.status_code}: {r.text[:200]}")
    return r.content.decode("utf-8", errors="replace")


def resolve_sandbox(jb: str, adapter: str, agent: str) -> str:
    """Prefer sandbox_id from EDPAgent process logs (same strategy as Adapter)."""
    log_root = Path(__file__).resolve().parent / "logs" / "edp_agent"
    files = sorted(log_root.glob("process_*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
    pat = re.compile(r"sandbox_id[=:\s]+([0-9a-z][0-9a-z_-]{2,15})", re.I)
    for path in files[:5]:
        try:
            text = path.read_bytes()[-512_000:].decode("utf-8", errors="ignore")
        except OSError:
            continue
        matches = pat.findall(text)
        if matches:
            return matches[-1]

    with client() as c:
        boxes = c.get(f"{jb.rstrip('/')}/api/v1/sandboxes").json()
    if isinstance(boxes, dict):
        boxes = boxes.get("items") or []
    ready = [b for b in boxes if str(b.get("phase", "")).lower() == "ready"]
    if len(ready) == 1:
        return str(ready[0]["id"])
    if not ready:
        raise RuntimeError("no ready sandbox")
    for b in ready:
        try:
            jb_download(jb, str(b["id"]), REMOTE)
            return str(b["id"])
        except Exception:
            continue
    return str(ready[0]["id"])


def make_skill_doc(marker: str) -> str:
    return f"""---
name: {SKILL}
description: E2E hotupdate probe skill. Contains HOTUPDATE_MARKER for verification.
---

# Product Recommend Skill (E2E Probe)

HOTUPDATE_MARKER={marker}

When asked to verify skill content, use read_file on this file and quote the HOTUPDATE_MARKER line exactly.
"""


def ask_quote_marker() -> str:
    return (
        "这是联调探针，不要做理财推荐。"
        f"请立刻用 read_file 工具读取文件 `{REMOTE}`，"
        "在最终回答里原样输出文件中以 `HOTUPDATE_MARKER=` 开头的那一行（整行复制，不要改写）。"
        "不要调用 call_mcp/call_versatile/ask_user。"
    )


def render_html(results: list[CheckResult], meta: dict) -> str:
    rows = []
    for r in results:
        badge = "PASS" if r.passed else "FAIL"
        cls = "pass" if r.passed else "fail"
        ev = json.dumps(r.evidence, ensure_ascii=False, indent=2)
        rows.append(
            f"<tr class='{cls}'><td>{badge}</td><td>{r.name}</td>"
            f"<td>{r.duration_ms} ms</td><td><pre>{_esc(r.detail)}</pre></td>"
            f"<td><pre>{_esc(ev)}</pre></td></tr>"
        )
    passed = sum(1 for r in results if r.passed)
    total = len(results)
    overall = "PASS" if passed == total and total else "FAIL"
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<title>对话联调报告：轨迹/代理/Skill热更</title>
<style>
body{{font-family:"Segoe UI","PingFang SC",sans-serif;margin:24px;background:#f6f8fa}}
.summary{{padding:12px 16px;border-radius:8px;display:inline-block;margin:12px 0}}
.summary.pass{{background:#dafbe1;color:#116329}}.summary.fail{{background:#ffebe9;color:#a40e26}}
table{{border-collapse:collapse;width:100%;background:#fff}}
th,td{{border:1px solid #d0d7de;padding:10px;vertical-align:top;text-align:left}}
th{{background:#f0f3f6}} pre{{white-space:pre-wrap;word-break:break-word;margin:0;font-size:12px}}
tr.pass td:first-child{{color:#116329;font-weight:700}} tr.fail td:first-child{{color:#a40e26;font-weight:700}}
</style></head><body>
<h1>对话联调报告：轨迹采集 / 调用代理 / Skill 热更</h1>
<p>UTC {meta.get('generated_at_utc')} · 模型 {meta.get('model_name')} @ {meta.get('model_api_base')}（密钥已脱敏）</p>
<div class="summary {'pass' if overall=='PASS' else 'fail'}">总体：{overall}（{passed}/{total}）</div>
<table><thead><tr><th>结果</th><th>检查项</th><th>耗时</th><th>详情</th><th>证据</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table>
<h2>元数据</h2><pre>{_esc(json.dumps(meta, ensure_ascii=False, indent=2))}</pre>
</body></html>"""


def _esc(s: object) -> str:
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--adapter-url", default="http://127.0.0.1:18900")
    p.add_argument("--edp-url", default="http://127.0.0.1:18001")
    p.add_argument("--jiuwenbox-url", default="http://127.0.0.1:8321")
    p.add_argument("--agent-name", default="edp_agent")
    args = p.parse_args()

    marker_a = f"BEFORE_{uuid.uuid4().hex[:8].upper()}"
    marker_b = f"AFTER_{uuid.uuid4().hex[:8].upper()}"
    results: list[CheckResult] = []

    # health
    for name, url in [
        ("jiuwenbox /health", f"{args.jiuwenbox_url}/health"),
        ("EDPAgent /health", f"{args.edp_url}/health"),
        ("Adapter /health", f"{args.adapter_url}/health"),
    ]:
        t0 = time.perf_counter()
        with client() as c:
            r = c.get(url)
        results.append(
            CheckResult(
                name=name,
                passed=r.status_code == 200,
                detail=f"HTTP {r.status_code} {r.text[:160]}",
                duration_ms=int((time.perf_counter() - t0) * 1000),
                evidence={"status": r.status_code},
            )
        )

    sandbox_id = resolve_sandbox(args.jiuwenbox_url, args.adapter_url, args.agent_name)

    # ── 1) seed MARKER_A via Adapter update (or upload if needed) ──
    t0 = time.perf_counter()
    doc_a = make_skill_doc(marker_a)
    # ensure skill exists for update contract: if missing, upload via jiuwenbox then update
    try:
        jb_download(args.jiuwenbox_url, sandbox_id, REMOTE)
        exists = True
    except Exception:
        exists = False
    if not exists:
        with client() as c:
            up = c.post(
                f"{args.jiuwenbox_url.rstrip('/')}/api/v1/sandboxes/{sandbox_id}/upload",
                params={"sandbox_path": REMOTE},
                files={"file": ("SKILL.md", doc_a.encode("utf-8"), "text/plain")},
            )
        seeded = up.status_code in (200, 204)
    else:
        upd = skill_action(
            args.adapter_url,
            {
                "action": "update_skill",
                "agent_name": args.agent_name,
                "skill_name": SKILL,
                "skill_content": doc_a,
            },
        )
        seeded = upd.get("_http") == 200 and upd.get("success") is True
        if not seeded:
            # fallback raw upload
            with client() as c:
                up = c.post(
                    f"{args.jiuwenbox_url.rstrip('/')}/api/v1/sandboxes/{sandbox_id}/upload",
                    params={"sandbox_path": REMOTE},
                    files={"file": ("SKILL.md", doc_a.encode("utf-8"), "text/plain")},
                )
            seeded = up.status_code in (200, 204)

    on_disk_a = jb_download(args.jiuwenbox_url, sandbox_id, REMOTE)
    results.append(
        CheckResult(
            name="准备 Skill 内容 MARKER_A",
            passed=seeded and marker_a in on_disk_a,
            detail=f"seeded={seeded}; marker_in_sandbox={marker_a in on_disk_a}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={"sandbox_id": sandbox_id, "marker_a": marker_a, "path": REMOTE},
        )
    )

    # ── 2) dialogue A: call proxy + quote MARKER_A ──
    t0 = time.perf_counter()
    query_a = ask_quote_marker()
    conv_a, resp_a = invoke(args.adapter_url, args.agent_name, query_a)
    answer_a = str(resp_a.get("answer") or "")
    proxy_a_ok = resp_a.get("http") == 200 and resp_a.get("success") is True
    saw_a = marker_a in answer_a
    saw_b_in_a = marker_b in answer_a
    results.append(
        CheckResult(
            name="调用代理 · 对话A（应读到 MARKER_A）",
            passed=proxy_a_ok and saw_a and not saw_b_in_a,
            detail=f"success={resp_a.get('success')} saw_A={saw_a} answer={answer_a[:240]}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={
                "conversation_id": conv_a,
                "user_query": query_a,
                "http": resp_a.get("http"),
                "success": resp_a.get("success"),
                "error": resp_a.get("error"),
                "event_count": len(resp_a.get("events") or []),
                "answer": answer_a[:500],
            },
        )
    )

    # ── 3) hot-update to MARKER_B ──
    t0 = time.perf_counter()
    doc_b = make_skill_doc(marker_b)
    upd_b = skill_action(
        args.adapter_url,
        {
            "action": "update_skill",
            "agent_name": args.agent_name,
            "skill_name": SKILL,
            "skill_content": doc_b,
        },
    )
    on_disk_b = jb_download(args.jiuwenbox_url, sandbox_id, REMOTE)
    hot_ok = (
        upd_b.get("_http") == 200
        and upd_b.get("success") is True
        and marker_b in on_disk_b
        and marker_a not in on_disk_b
    )
    results.append(
        CheckResult(
            name="Skill 热更 API → 沙箱文件变为 MARKER_B",
            passed=hot_ok,
            detail=f"update_http={upd_b.get('_http')} success={upd_b.get('success')} disk_has_B={marker_b in on_disk_b}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={
                "update_body": {k: v for k, v in upd_b.items() if k != "raw"},
                "sandbox_preview": on_disk_b[:300],
            },
        )
    )

    # ── 4) dialogue B: must quote MARKER_B ──
    t0 = time.perf_counter()
    query_b = ask_quote_marker()
    conv_b, resp_b = invoke(args.adapter_url, args.agent_name, query_b)
    answer_b = str(resp_b.get("answer") or "")
    proxy_b_ok = resp_b.get("http") == 200 and resp_b.get("success") is True
    saw_b = marker_b in answer_b
    saw_a_in_b = marker_a in answer_b
    results.append(
        CheckResult(
            name="调用代理 · 对话B（应读到 MARKER_B，证明热更生效）",
            passed=proxy_b_ok and saw_b and not saw_a_in_b,
            detail=f"success={resp_b.get('success')} saw_B={saw_b} saw_old_A={saw_a_in_b} answer={answer_b[:240]}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={
                "conversation_id": conv_b,
                "user_query": query_b,
                "http": resp_b.get("http"),
                "success": resp_b.get("success"),
                "error": resp_b.get("error"),
                "event_count": len(resp_b.get("events") or []),
                "answer": answer_b[:500],
                "marker_a": marker_a,
                "marker_b": marker_b,
            },
        )
    )

    # ── 5) traces for both conversations ──
    t0 = time.perf_counter()
    time.sleep(2)
    tr_a = get_traces(args.adapter_url, args.agent_name, conv_a)
    tr_b = get_traces(args.adapter_url, args.agent_name, conv_b)
    traces_ok = (tr_a.get("total") or 0) > 0 and (tr_b.get("total") or 0) > 0
    results.append(
        CheckResult(
            name="轨迹采集（对话A+B 均有 traces）",
            passed=bool(traces_ok),
            detail=f"A total={tr_a.get('total')} B total={tr_b.get('total')}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={"conv_a": conv_a, "trace_a": tr_a.get("total"), "conv_b": conv_b, "trace_b": tr_b.get("total")},
        )
    )

    meta = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "adapter_url": args.adapter_url,
        "edp_url": args.edp_url,
        "jiuwenbox_url": args.jiuwenbox_url,
        "sandbox_id": sandbox_id,
        "skill": SKILL,
        "marker_a": marker_a,
        "marker_b": marker_b,
        "model_name": os.getenv("PLANNING_AGENT_MODEL_NAME", "example-model"),
        "model_api_base": os.getenv("PLANNING_AGENT_MODEL_BASE_URL", "https://api.example-llm.com/v1"),
        "checks_passed": sum(1 for r in results if r.passed),
        "checks_total": len(results),
        "proof": "对话A回答含 MARKER_A；热更后对话B回答含 MARKER_B 且不含 MARKER_A",
    }
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    html_path = REPORT_DIR / f"dialogue_e2e_{stamp}.html"
    json_path = REPORT_DIR / f"dialogue_e2e_{stamp}.json"
    html_path.write_text(render_html(results, meta), encoding="utf-8")
    json_path.write_text(
        json.dumps({"meta": meta, "results": [asdict(r) for r in results]}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"REPORT_HTML={html_path}")
    print(f"REPORT_JSON={json_path}")
    for r in results:
        print(f"[{'PASS' if r.passed else 'FAIL'}] {r.name}: {r.detail}")
    return 0 if all(r.passed for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
