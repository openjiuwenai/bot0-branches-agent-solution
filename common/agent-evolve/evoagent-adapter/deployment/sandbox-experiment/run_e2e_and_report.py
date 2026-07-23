#!/usr/bin/env python3
"""Full three-container E2E verification + HTML report.

Validates Adapter capabilities against live EDPAgent(sandbox)+jiuwenbox+Adapter:
  1) call proxy  2) trace collection  3) skill hot-update via jiuwenbox API

Secrets (LLM api_key) must come from environment — never written into the HTML report.
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

_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_ROOT / "src"))

REPORT_DIR = Path(__file__).resolve().parent / "reports"


@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str
    duration_ms: int = 0
    evidence: dict = field(default_factory=dict)


def _client() -> httpx.Client:
    return httpx.Client(timeout=120.0, trust_env=False)


def check_health(name: str, url: str) -> CheckResult:
    t0 = time.perf_counter()
    try:
        with _client() as c:
            r = c.get(url)
        ok = r.status_code == 200
        return CheckResult(
            name=name,
            passed=ok,
            detail=f"HTTP {r.status_code}: {r.text[:200]}",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={"url": url, "status": r.status_code},
        )
    except Exception as exc:
        return CheckResult(
            name=name,
            passed=False,
            detail=str(exc),
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={"url": url},
        )


def check_call_proxy(adapter_url: str, agent_name: str, query: str) -> CheckResult:
    conv_id = f"e2e-{uuid.uuid4().hex[:12]}"
    url = f"{adapter_url.rstrip('/')}/api/v1/agents/{agent_name}/conversations/{conv_id}"
    t0 = time.perf_counter()
    try:
        with _client() as c:
            r = c.post(url, json={"query": query}, timeout=180.0)
        body = {}
        try:
            body = r.json()
        except Exception:
            body = {"raw": r.text[:500]}
        # success path: HTTP 200 with structured response; business may still return success=false
        transport_ok = r.status_code == 200 and isinstance(body, dict)
        # Prefer explicit success if present; else accept non-empty answer / events
        business_ok = False
        if transport_ok:
            if body.get("success") is True:
                business_ok = True
            elif body.get("answer") or body.get("final_answer") or body.get("events"):
                business_ok = True
            elif body.get("error") and "无法连接" in str(body.get("error")):
                business_ok = False
            elif "detail" in body and "不存在" in str(body.get("detail")):
                business_ok = False
            else:
                # Got a parsed AgentCallResponse-like payload without connect error
                business_ok = "conversation_id" in body or "success" in body
        passed = transport_ok and business_ok
        detail = (
            f"HTTP {r.status_code}; success={body.get('success')}; "
            f"error={body.get('error') or body.get('detail')}; "
            f"answer_preview={str(body.get('answer') or body.get('final_answer') or '')[:160]}"
        )
        return CheckResult(
            name="调用代理 (call proxy)",
            passed=passed,
            detail=detail,
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={
                "conversation_id": conv_id,
                "http_status": r.status_code,
                "success": body.get("success"),
                "has_answer": bool(body.get("answer") or body.get("final_answer")),
                "event_count": len(body.get("events") or body.get("event_summaries") or []),
                "error": body.get("error") or body.get("detail"),
            },
        )
    except Exception as exc:
        return CheckResult(
            name="调用代理 (call proxy)",
            passed=False,
            detail=str(exc),
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence={"conversation_id": conv_id},
        )


def check_trace_collection(
    adapter_url: str,
    agent_name: str,
    conversation_id: str | None,
    poll_seconds: int = 20,
) -> CheckResult:
    t0 = time.perf_counter()
    traces_url = f"{adapter_url.rstrip('/')}/api/v1/agents/{agent_name}/traces"
    cleaned_url = None
    if conversation_id:
        cleaned_url = (
            f"{adapter_url.rstrip('/')}/api/v1/agents/{agent_name}"
            f"/cleaned-traces/{conversation_id}"
        )
    try:
        deadline = time.time() + poll_seconds
        last_detail = ""
        found = False
        evidence: dict = {}
        with _client() as c:
            # Trigger an immediate poll by hitting status / waiting for background poll
            while time.time() < deadline:
                r = c.get(traces_url)
                evidence["traces_http"] = r.status_code
                if r.status_code == 200:
                    data = r.json()
                    items = data if isinstance(data, list) else (
                        data.get("conversation_ids")
                        or data.get("items")
                        or data.get("conversations")
                        or data.get("traces")
                        or []
                    )
                    evidence["trace_list_count"] = len(items) if isinstance(items, list) else 0
                    evidence["conversation_ids_sample"] = (items[:5] if isinstance(items, list) else [])
                    if conversation_id and cleaned_url:
                        # Also check raw traces for this conversation
                        tr = c.get(
                            f"{adapter_url.rstrip('/')}/api/v1/agents/{agent_name}/traces/{conversation_id}"
                        )
                        evidence["trace_detail_http"] = tr.status_code
                        if tr.status_code == 200:
                            td = tr.json()
                            evidence["trace_total"] = td.get("total")
                            if (td.get("total") or 0) > 0 or td.get("calls"):
                                found = True
                                last_detail = f"traces for {conversation_id}: total={td.get('total')}"
                                break
                        cr = c.get(cleaned_url)
                        evidence["cleaned_http"] = cr.status_code
                        if cr.status_code == 200:
                            cleaned = cr.json()
                            evidence["cleaned_keys"] = (
                                list(cleaned.keys())[:20] if isinstance(cleaned, dict) else type(cleaned).__name__
                            )
                            # cleaned may exist even with empty messages; prefer raw total>0
                            if found:
                                break
                            if isinstance(cleaned, dict) and (
                                cleaned.get("messages") or cleaned.get("trajectory") or cleaned.get("task_input")
                            ):
                                found = True
                                last_detail = f"cleaned-traces OK for {conversation_id}"
                                break
                        last_detail = (
                            f"waiting traces for {conversation_id}; "
                            f"list_count={evidence.get('trace_list_count')}; "
                            f"detail_http={evidence.get('trace_detail_http')}"
                        )
                    elif isinstance(items, list) and items:
                        found = True
                        last_detail = f"traces list non-empty ({len(items)})"
                        break
                    else:
                        last_detail = "waiting for traces..."
                else:
                    last_detail = f"traces HTTP {r.status_code}: {r.text[:120]}"
                time.sleep(2)
        return CheckResult(
            name="轨迹采集 (trace collection)",
            passed=found,
            detail=last_detail or "no traces",
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence=evidence,
        )
    except Exception as exc:
        return CheckResult(
            name="轨迹采集 (trace collection)",
            passed=False,
            detail=str(exc),
            duration_ms=int((time.perf_counter() - t0) * 1000),
        )


def check_skill_hotupdate(
    adapter_url: str,
    agent_name: str,
    jiuwenbox_url: str,
    skill_name: str | None = None,
) -> CheckResult:
    t0 = time.perf_counter()
    skills_url = f"{adapter_url.rstrip('/')}/api/v1/skills"
    marker = f"hotupdate-marker-{uuid.uuid4().hex[:8]}"
    evidence: dict = {}
    try:
        with _client() as c:
            # list
            lr = c.post(skills_url, json={"action": "skill_list", "agent_name": agent_name})
            evidence["skill_list_http"] = lr.status_code
            if lr.status_code != 200:
                return CheckResult(
                    name="Skill 热更 (jiuwenbox)",
                    passed=False,
                    detail=f"skill_list HTTP {lr.status_code}: {lr.text[:300]}",
                    duration_ms=int((time.perf_counter() - t0) * 1000),
                    evidence=evidence,
                )
            listed = lr.json()
            skills = listed.get("skills") or listed.get("data", {}).get("skills") or []
            if isinstance(skills, list) and skills and isinstance(skills[0], dict):
                names = [s.get("name") or s.get("skill_name") for s in skills]
            elif isinstance(skills, list):
                names = [str(s) for s in skills]
            else:
                # try another shape
                names = listed.get("skill_names") or []
            evidence["skills"] = names
            if not skill_name:
                skill_name = next((n for n in names if n), None)
            if not skill_name:
                return CheckResult(
                    name="Skill 热更 (jiuwenbox)",
                    passed=False,
                    detail=f"no skills listed: {listed}",
                    duration_ms=int((time.perf_counter() - t0) * 1000),
                    evidence=evidence,
                )

            # read
            rr = c.post(
                skills_url,
                json={
                    "action": "skill_content",
                    "agent_name": agent_name,
                    "skill_name": skill_name,
                },
            )
            evidence["skill_content_http"] = rr.status_code
            if rr.status_code != 200:
                return CheckResult(
                    name="Skill 热更 (jiuwenbox)",
                    passed=False,
                    detail=f"skill_content HTTP {rr.status_code}: {rr.text[:300]}",
                    duration_ms=int((time.perf_counter() - t0) * 1000),
                    evidence=evidence,
                )
            content = rr.json().get("content") or rr.json().get("data", {}).get("content") or ""
            evidence["content_len_before"] = len(content)

            new_content = content.rstrip() + f"\n\n<!-- {marker} -->\n"
            ur = c.post(
                skills_url,
                json={
                    "action": "update_skill",
                    "agent_name": agent_name,
                    "skill_name": skill_name,
                    "skill_content": new_content,
                },
            )
            evidence["update_http"] = ur.status_code
            evidence["update_body"] = ur.text[:300]
            if ur.status_code != 200:
                return CheckResult(
                    name="Skill 热更 (jiuwenbox)",
                    passed=False,
                    detail=f"update_skill HTTP {ur.status_code}: {ur.text[:300]}",
                    duration_ms=int((time.perf_counter() - t0) * 1000),
                    evidence=evidence,
                )

            # Confirm via jiuwenbox download (discover sandbox)
            jb = c.get(f"{jiuwenbox_url.rstrip('/')}/api/v1/sandboxes")
            sandboxes = jb.json() if jb.status_code == 200 else []
            if isinstance(sandboxes, dict):
                sandboxes = sandboxes.get("items") or []
            ready = [s for s in sandboxes if str(s.get("phase", "")).lower() == "ready"]
            evidence["ready_sandboxes"] = [s.get("id") for s in ready]
            confirmed = False
            for s in ready:
                sid = s["id"]
                path = f"/tmp/skills/{skill_name}/SKILL.md"
                dr = c.get(
                    f"{jiuwenbox_url.rstrip('/')}/api/v1/sandboxes/{sid}/download",
                    params={"sandbox_path": path},
                )
                if dr.status_code == 200 and marker.encode() in dr.content:
                    confirmed = True
                    evidence["confirmed_sandbox_id"] = sid
                    evidence["confirmed_path"] = path
                    break

            # restore
            rest = c.post(
                skills_url,
                json={
                    "action": "restore_skill",
                    "agent_name": agent_name,
                    "skill_names": [skill_name],
                },
            )
            evidence["restore_http"] = rest.status_code

            passed = confirmed
            detail = (
                f"skill={skill_name}; marker_in_sandbox={confirmed}; "
                f"restore_http={rest.status_code}"
            )
            return CheckResult(
                name="Skill 热更 (jiuwenbox)",
                passed=passed,
                detail=detail,
                duration_ms=int((time.perf_counter() - t0) * 1000),
                evidence=evidence,
            )
    except Exception as exc:
        return CheckResult(
            name="Skill 热更 (jiuwenbox)",
            passed=False,
            detail=str(exc),
            duration_ms=int((time.perf_counter() - t0) * 1000),
            evidence=evidence,
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
    overall = "PASS" if passed == total and total > 0 else "FAIL"
    meta_json = json.dumps(meta, ensure_ascii=False, indent=2)
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<title>EvoAgentAdapter 三容器能力验证报告</title>
<style>
body {{ font-family: "Segoe UI", "PingFang SC", sans-serif; margin: 24px; background: #f6f8fa; color: #24292f; }}
h1 {{ margin-bottom: 4px; }}
.meta {{ color: #57606a; margin-bottom: 16px; }}
.summary {{ padding: 12px 16px; border-radius: 8px; display: inline-block; margin-bottom: 16px; }}
.summary.pass {{ background: #dafbe1; color: #116329; }}
.summary.fail {{ background: #ffebe9; color: #a40e26; }}
table {{ border-collapse: collapse; width: 100%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.08); }}
th, td {{ border: 1px solid #d0d7de; padding: 10px; vertical-align: top; text-align: left; }}
th {{ background: #f0f3f6; }}
tr.pass td:first-child {{ color: #116329; font-weight: 700; }}
tr.fail td:first-child {{ color: #a40e26; font-weight: 700; }}
pre {{ white-space: pre-wrap; word-break: break-word; margin: 0; font-size: 12px; }}
.note {{ margin-top: 16px; font-size: 13px; color: #57606a; }}
</style>
</head>
<body>
<h1>EvoAgentAdapter 三容器能力验证报告</h1>
<p class="meta">生成时间（UTC）：{meta.get("generated_at_utc")} · 环境：Docker · 模型：{meta.get("model_name")}（密钥已脱敏）</p>
<div class="summary {'pass' if overall == 'PASS' else 'fail'}">总体结果：{overall}（{passed}/{total}）</div>
{f'<p class="note" style="color:#9a6700;background:#fff8c5;padding:8px 12px;border-radius:6px;">注意：{ _esc(meta.get("model_auth_note")) }</p>' if meta.get("model_auth_note") else ""}
<table>
<thead><tr><th>结果</th><th>检查项</th><th>耗时</th><th>详情</th><th>证据</th></tr></thead>
<tbody>
{''.join(rows)}
</tbody>
</table>
<h2>环境元数据</h2>
<pre>{_esc(meta_json)}</pre>
<p class="note">说明：Skill 热更走 jiuwenbox File API（非宿主机三方挂载）。轨迹采集依赖 EDPAgent process_*.log 挂载与 Adapter 轮询。报告不含 API Key。</p>
</body>
</html>
"""


def _esc(text: str) -> str:
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--adapter-url", default=os.getenv("ADAPTER_URL", "http://127.0.0.1:8900"))
    p.add_argument("--jiuwenbox-url", default=os.getenv("JIUWENBOX_URL", "http://127.0.0.1:8321"))
    p.add_argument("--edp-url", default=os.getenv("EDP_URL", "http://127.0.0.1:18001"))
    p.add_argument("--agent-name", default="edp_agent")
    p.add_argument("--query", default="你好，请用一句话介绍你自己。")
    p.add_argument("--skill-name", default="")
    p.add_argument("--trace-wait", type=int, default=25)
    args = p.parse_args()

    results: list[CheckResult] = []
    results.append(check_health("jiuwenbox /health", f"{args.jiuwenbox_url.rstrip('/')}/health"))
    results.append(check_health("EDPAgent /health", f"{args.edp_url.rstrip('/')}/health"))
    results.append(check_health("Adapter /health", f"{args.adapter_url.rstrip('/')}/health"))

    call = check_call_proxy(args.adapter_url, args.agent_name, args.query)
    results.append(call)
    conv_id = call.evidence.get("conversation_id")

    # Give adapter poll loop time; also try forcing by waiting
    time.sleep(3)
    results.append(
        check_trace_collection(
            args.adapter_url,
            args.agent_name,
            str(conv_id) if conv_id else None,
            poll_seconds=args.trace_wait,
        )
    )
    results.append(
        check_skill_hotupdate(
            args.adapter_url,
            args.agent_name,
            args.jiuwenbox_url,
            skill_name=args.skill_name or None,
        )
    )

    # Annotate model-auth issues without failing proxy capability check
    model_auth_ok = True
    for r in results:
        if "Access denied" in r.detail or "BadRequestError" in r.detail:
            model_auth_ok = False
            break

    meta = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "adapter_url": args.adapter_url,
        "edp_url": args.edp_url,
        "jiuwenbox_url": args.jiuwenbox_url,
        "agent_name": args.agent_name,
        "model_name": os.getenv("PLANNING_AGENT_MODEL_NAME", "example-model"),
        "model_api_base": os.getenv(
            "PLANNING_AGENT_MODEL_BASE_URL",
            "https://api.example-llm.com/v1",
        ),
        "model_auth_ok": model_auth_ok,
        "model_auth_note": (
            None
            if model_auth_ok
            else "LLM 返回 Access denied：调用代理链路已通，但模型鉴权失败（请核对 api_key 是否为 LLM 服务可用密钥）"
        ),
        "skill_backend": "jiuwenbox",
        "checks_passed": sum(1 for r in results if r.passed),
        "checks_total": len(results),
        "topology": {
            "jiuwenbox": "docker:jiuwenbox:8321",
            "edpagent": "docker:edpagent sandbox mode SANDBOX_URL→jiuwenbox",
            "adapter": "docker:evo-adapter skill_backend=jiuwenbox",
            "redis": "docker:redis",
        },
    }
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    html_path = REPORT_DIR / f"e2e_report_{stamp}.html"
    json_path = REPORT_DIR / f"e2e_report_{stamp}.json"
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
