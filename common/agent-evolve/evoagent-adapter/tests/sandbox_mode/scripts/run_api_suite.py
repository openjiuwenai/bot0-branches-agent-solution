"""Sandbox-mode API suite (TC-01 ~ TC-14).

Industry test levels covered by this script:
  - Smoke (TC-01~03): component /health
  - Integration (TC-04~07, 10~14): Skill API ↔ jiuwenbox FS / contracts
  - System (TC-08~09): call proxy + traces with real LLM

Usage:
    python run_api_suite.py
    python run_api_suite.py --adapter-url http://127.0.0.1:18900
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from config import SandboxModeConfig, parse_sandbox_args
else:
    from .config import SandboxModeConfig, parse_sandbox_args

REPORT_DIR = Path(__file__).resolve().parents[1] / "reports"
_SANDBOX_ID_RE = re.compile(
    r"sandbox_id[=:\s]+([0-9a-z][0-9a-z_-]{2,15})",
    re.IGNORECASE,
)


@dataclass
class Result:
    case_id: str
    name: str
    passed: bool
    detail: str = ""
    duration_ms: int = 0


def api(
    base: str,
    method: str,
    path: str,
    body: dict | None = None,
    *,
    timeout: int = 120,
    query: dict[str, str] | None = None,
) -> tuple[int, Any]:
    url = f"{base.rstrip('/')}{path}"
    if query:
        url = f"{url}?{urllib.parse.urlencode(query)}"
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
    if len(ready) == 1:
        return str(ready[0].get("id"))
    if len(ready) > 1:
        # Prefer id that appears in recent adapter-visible logs if present on host
        return str(ready[-1].get("id"))
    return None


def jb_download(cfg: SandboxModeConfig, sandbox_id: str, path: str) -> tuple[int, str]:
    url = (
        f"{cfg.jiuwenbox_url_normalized}/api/v1/sandboxes/"
        f"{urllib.parse.quote(sandbox_id)}/download"
        f"?{urllib.parse.urlencode({'sandbox_path': path})}"
    )
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as e:
        return 0, str(e)


def run_all(cfg: SandboxModeConfig) -> list[Result]:
    results: list[Result] = []
    skill = cfg.skill_name
    agent = cfg.agent_name

    def _add(case_id: str, name: str, passed: bool, detail: str, t0: float) -> None:
        results.append(
            Result(
                case_id=case_id,
                name=name,
                passed=passed,
                detail=detail,
                duration_ms=int((time.perf_counter() - t0) * 1000),
            )
        )

    # TC-01 jiuwenbox health
    t0 = time.perf_counter()
    code, data = api(cfg.jiuwenbox_url_normalized, "GET", "/health", timeout=15)
    _add("TC-01", "[冒烟] jiuwenbox /health", code == 200 and data.get("status") == "ok", f"code={code} {data}", t0)

    # TC-02 EDP health
    t0 = time.perf_counter()
    code, data = api(cfg.edp_url_normalized, "GET", "/health", timeout=15)
    _add(
        "TC-02",
        "[冒烟] EDPAgent /health",
        code == 200 and data.get("status") in ("healthy", "ok"),
        f"code={code} {data}",
        t0,
    )

    # TC-03 Adapter health
    t0 = time.perf_counter()
    code, data = api(cfg.adapter_url_normalized, "GET", "/health", timeout=15)
    _add("TC-03", "[冒烟] Adapter /health", code == 200 and data.get("status") == "ok", f"code={code} {data}", t0)

    # TC-04 skill_list
    t0 = time.perf_counter()
    code, data = skill_action(cfg, "skill_list")
    names = [s.get("name") for s in data.get("skills", [])]
    _add("TC-04", "[集成] skill_list 返回目标 skill（jiuwenbox 后端）", code == 200 and skill in names, f"skills={names}", t0)

    # TC-05 skill_content — also normalize to snapshot baseline so TC-10 is deterministic
    t0 = time.perf_counter()
    skill_action(cfg, "restore_skill", skill_names=[skill])
    code, data = skill_action(cfg, "skill_content", skill_name=skill)
    content = data.get("content", "")
    ok = code == 200 and len(content) > 50
    _add("TC-05", "[集成] skill_content 返回完整 Markdown", ok, f"len={len(content)}", t0)

    # TC-06 update + readback via Adapter
    t0 = time.perf_counter()
    marker = f"<!-- SANDBOX_HOTUPDATE_TC06_{int(time.time())} -->"
    patched = content.replace("\n\n", f"\n\n{marker}\n\n", 1) if content else content + marker
    code, upd = skill_action(cfg, "update_skill", skill_name=skill, skill_content=patched)
    code2, rb = skill_action(cfg, "skill_content", skill_name=skill)
    ok = code == 200 and upd.get("success") is True and marker in rb.get("content", "")
    _add("TC-06", "[集成] update_skill 经 Adapter 写沙箱且 skill_content 读回", ok, f"success={upd.get('success')}", t0)

    # TC-07 jiuwenbox download confirms sandbox FS
    t0 = time.perf_counter()
    sid = resolve_sandbox_id(cfg)
    jb_code, jb_text = (0, "")
    if sid:
        jb_code, jb_text = jb_download(cfg, sid, cfg.remote_skill_md)
    ok = bool(sid) and jb_code == 200 and marker in jb_text
    _add(
        "TC-07",
        "[集成] jiuwenbox download 确认沙箱 FS 含热更标记",
        ok,
        f"sandbox_id={sid} http={jb_code} marker_in_file={marker in jb_text}",
        t0,
    )

    # TC-08 call proxy
    t0 = time.perf_counter()
    conv = f"sbx-tc08-{int(time.time())}"
    code, call = api(
        cfg.adapter_url_normalized,
        "POST",
        f"/api/v1/agents/{agent}/conversations/{conv}",
        {
            "query": (
                "这是联调探针，不要做理财推荐。"
                f"请立刻用 read_file 读取 `{cfg.remote_skill_md}`，"
                "在最终回答里原样输出文件中以 `HOTUPDATE_MARKER=` 开头的那一行"
                "（若没有该行则输出文件前 3 行）。不要调用 call_mcp/call_versatile/ask_user。"
            )
        },
        timeout=300,
    )
    ok = code == 200 and call.get("success") is True
    _add("TC-08", "[系统] 调用代理：新会话对话成功（含 LLM）", ok, f"conv={conv} success={call.get('success')}", t0)

    # TC-09 traces
    t0 = time.perf_counter()
    time.sleep(2)
    # trigger poll
    api(cfg.adapter_url_normalized, "GET", f"/api/v1/agents/{agent}/traces", timeout=30)
    code, traces = api(
        cfg.adapter_url_normalized,
        "GET",
        f"/api/v1/agents/{agent}/traces/{conv}",
        timeout=60,
    )
    calls = traces.get("calls") or []
    total = traces.get("total")
    if total is None:
        total = len(calls)
    ok = code == 200 and int(total) > 0
    _add("TC-09", "[系统] 轨迹采集：单次对话 traces 可采集", ok, f"http={code} total={total} calls={len(calls)}", t0)

    # TC-10 restore
    t0 = time.perf_counter()
    code, restored = skill_action(cfg, "restore_skill", skill_names=[skill])
    item = (restored.get("restored") or [{}])[0]
    code2, after = skill_action(cfg, "skill_content", skill_name=skill)
    ok = (
        code == 200
        and item.get("success") is True
        and after.get("content") == content
        and marker not in after.get("content", "")
    )
    _add(
        "TC-10",
        "[集成] restore_skill 恢复到首次热更前快照",
        ok,
        f"success={item.get('success')} len_before={len(content)} len_after={len(after.get('content', ''))}",
        t0,
    )

    # TC-11 restore idempotent
    t0 = time.perf_counter()
    code, restored2 = skill_action(cfg, "restore_skill", skill_names=[skill])
    item2 = (restored2.get("restored") or [{}])[0]
    _add("TC-11", "[集成] restore_skill 幂等（快照保留）", code == 200 and item2.get("success") is True, str(item2), t0)

    # TC-12 SKILL_NOT_FOUND
    t0 = time.perf_counter()
    code, bad = skill_action(
        cfg,
        "update_skill",
        skill_name="nonexistent_skill_xyz",
        skill_content="# x",
    )
    err = (bad.get("error") or {}) if isinstance(bad, dict) else {}
    ok = code == 404 or err.get("code") == "SKILL_NOT_FOUND" or bad.get("success") is False
    _add("TC-12", "[集成] 更新不存在 skill 返回错误", ok, f"code={code} body={bad}", t0)

    # TC-13 unknown agent
    t0 = time.perf_counter()
    code, bad = api(
        cfg.adapter_url_normalized,
        "POST",
        "/api/v1/skills",
        {"agent_name": "no_such_agent", "action": "skill_list"},
    )
    _add("TC-13", "[集成] 未知 agent_name 返回 404", code == 404, f"code={code}", t0)

    # TC-14 invalid action
    t0 = time.perf_counter()
    code, bad = api(
        cfg.adapter_url_normalized,
        "POST",
        "/api/v1/skills",
        {"agent_name": agent, "action": "invalid_action"},
    )
    _add("TC-14", "[集成] 非法 action 返回 400", code == 400, f"code={code}", t0)

    return results


def main() -> int:
    cfg = parse_sandbox_args("Sandbox-mode API suite: smoke + integration + system (TC-01~14)")
    results = run_all(cfg)
    passed = sum(1 for r in results if r.passed)
    print("=" * 70)
    print(
        f"沙箱模式 API 套件（冒烟+集成+系统） | adapter={cfg.adapter_url_normalized} "
        f"| jiuwenbox={cfg.jiuwenbox_url_normalized} | agent={cfg.agent_name}"
    )
    print("=" * 70)
    for r in results:
        status = "PASS" if r.passed else "FAIL"
        print(f"  [{status}] {r.case_id} {r.name} ({r.duration_ms} ms)")
        if r.detail:
            print(f"         {r.detail}")
    print("=" * 70)
    print(f"合计: {passed}/{len(results)} 通过")

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = REPORT_DIR / f"api_suite_{stamp}.json"
    payload = {
        "meta": {
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "adapter_url": cfg.adapter_url_normalized,
            "edp_url": cfg.edp_url_normalized,
            "jiuwenbox_url": cfg.jiuwenbox_url_normalized,
            "agent_name": cfg.agent_name,
            "skill_name": cfg.skill_name,
            "passed": passed,
            "total": len(results),
        },
        "results": [asdict(r) for r in results],
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"REPORT_JSON={out}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
