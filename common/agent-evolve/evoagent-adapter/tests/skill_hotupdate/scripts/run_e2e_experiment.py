"""Skill hot-update E2E experiment: skill_content → update → converse → traces → restore.

Injects a high-priority verification block after YAML frontmatter and checks that
the business agent reflects the update in a **new** conversation without restart.

Usage:
    python run_e2e_experiment.py
    python run_e2e_experiment.py --base-url http://host:8900 --skill-name product_recommend_skill
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from config import HotUpdateConfig, parse_hotupdate_args
else:
    from .config import HotUpdateConfig, parse_hotupdate_args

VERIFY_BEGIN = "<!-- ADAPTER_HOTUPDATE_VERIFY_BEGIN -->"
VERIFY_END = "<!-- ADAPTER_HOTUPDATE_VERIFY_END -->"


def make_marker() -> str:
    return f"【SKILL热更-{datetime.now().strftime('%Y%m%d-%H%M%S')}】"


def split_frontmatter(content: str) -> tuple[str, str]:
    if not content.startswith("---"):
        return "", content
    match = re.match(r"^---\r?\n.*?\r?\n---\r?\n", content, re.DOTALL)
    if not match:
        return "", content
    end = match.end()
    return content[:end], content[end:]


def build_verification_block(marker: str) -> str:
    return f"""{VERIFY_BEGIN}

## 【最高优先级】热更新验证规约

> **优先级说明**：本段为 Adapter 热更新实验注入，**效力高于**下文一切「输出要求 / 输出模板 / 回复话术」。
> 实验结束后请调用 `restore_skill` 删除本段。

### 强制输出格式（面向用户的最终回复）

在「回复生成」步骤向用户输出时，**必须**严格遵守：

1. **第 1 行**：有且仅有（无空格前缀、无 `#` 标题、无「您好」等问候）：
   `{marker}`
2. **第 2 行**：空行
3. **第 3 行起**：再按本 Skill 原有业务流程输出（表格、推荐语、风险提示等照常）

### 正例

```
{marker}

为您推荐以下理财产品：
| 序号 | 产品名称 | ...
```

### 反例（禁止）

- 以「您好」「根据您的需求」开头后再出现标记
- 把标记放在表格后面或结尾
- 省略标记行

{VERIFY_END}
"""


def inject_verification(original: str, marker: str) -> str:
    frontmatter, body = split_frontmatter(original)
    block = build_verification_block(marker) + "\n\n"
    if frontmatter:
        return frontmatter + block + body.lstrip("\n")
    return block + original


def api(
    cfg: HotUpdateConfig,
    method: str,
    path: str,
    body: dict | None = None,
    timeout: int = 180,
) -> Any:
    url = f"{cfg.base_url_normalized}{path}"
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
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code} {path}: {raw}") from e


def skill_action(cfg: HotUpdateConfig, action: str, **extra: Any) -> Any:
    return api(
        cfg,
        "POST",
        "/api/v1/skills",
        {"agent_name": cfg.agent_name, "action": action, **extra},
    )


def extract_answer(call_resp: dict) -> str:
    if call_resp.get("answer"):
        return str(call_resp["answer"])
    for ev in call_resp.get("events") or []:
        if ev.get("type") == "final_answer_chunk" and ev.get("content"):
            return str(ev["content"])
    return ""


def extract_answer_from_traces(traces: dict) -> str:
    for rec in reversed(traces.get("calls", [])):
        if rec.get("type") != "GENERATION":
            continue
        output = rec.get("output")
        if isinstance(output, dict) and output.get("content"):
            return str(output["content"])
    return ""


def marker_at_answer_start(answer: str, marker: str) -> bool:
    lines = answer.lstrip().splitlines()
    return bool(lines) and lines[0].strip() == marker


def main() -> None:
    cfg = parse_hotupdate_args("Skill hot-update E2E verification experiment (TC-13~TC-14)")
    skill = cfg.skill_name
    agent = cfg.agent_name
    marker = make_marker()
    conv_id = f"skill-hotfix-{int(time.time())}"

    print("=" * 60)
    print("Skill 热更新实验（frontmatter 后注入验证规约）")
    print(f"  adapter: {cfg.base_url_normalized}")
    print(f"  agent:   {agent}")
    print(f"  skill:   {skill}")
    print(f"  marker:  {marker}")
    print(f"  conv:    {conv_id}")
    print("=" * 60)

    print("\n[1/6] 查询原始 Skill 内容...")
    original = skill_action(cfg, "skill_content", skill_name=skill)
    original_content = original["content"]
    print(f"  原始长度: {len(original_content)} 字符")

    print("\n[2/6] 热更新（frontmatter 后注入最高优先级规约）...")
    updated_content = inject_verification(original_content, marker)
    upd = skill_action(cfg, "update_skill", skill_name=skill, skill_content=updated_content)
    print(f"  update_skill: success={upd.get('success')}")
    print(f"  注入后长度: {len(updated_content)} 字符")

    print("\n[3/6] 读回确认...")
    read_back = skill_action(cfg, "skill_content", skill_name=skill)
    rb = read_back["content"]
    ok_readback = marker in rb and VERIFY_BEGIN in rb
    print(f"  读回含标记与注入块: {ok_readback}")
    if not ok_readback:
        print("  FAIL: 读回校验失败，中止")
        sys.exit(1)

    print("\n[4/6] 发起业务对话（新会话）...")
    query = "帮我推荐一款低风险、期限半年左右的理财产品"
    call_resp = api(
        cfg,
        "POST",
        f"/api/v1/agents/{agent}/conversations/{conv_id}",
        {"query": query},
        timeout=300,
    )
    answer = extract_answer(call_resp)
    if not answer.strip():
        traces_preview = api(cfg, "GET", f"/api/v1/agents/{agent}/traces/{conv_id}", timeout=60)
        answer = extract_answer_from_traces(traces_preview)
        print("  (answer 从 traces GENERATION 回退提取)")
    print(f"  success: {call_resp.get('success')}")
    marker_in_answer = marker in answer
    marker_first_line = marker_at_answer_start(answer, marker)
    print(f"  回答含标记:     {marker_in_answer}")
    print(f"  标记在第 1 行: {marker_first_line}")
    if answer:
        preview = answer[:350].replace("\n", "\\n")
        print(f"  回答预览: {preview}")

    print("\n[5/6] 查询 traces...")
    traces = api(cfg, "GET", f"/api/v1/agents/{agent}/traces/{conv_id}", timeout=60)
    skill_records = [c for c in traces.get("calls", []) if c.get("type") == "SKILL"]
    skill_hit = any(skill in str(r.get("input", {})) for r in skill_records)
    print(f"  SKILL 记录数: {len(skill_records)}, 命中目标 skill: {skill_hit}")

    print("\n[6/6] restore_skill 恢复...")
    restored = skill_action(cfg, "restore_skill", skill_names=[skill])
    for item in restored.get("restored", []):
        print(f"  {item.get('skill_name')}: success={item.get('success')}")
    after = skill_action(cfg, "skill_content", skill_name=skill)
    restored_ok = after["content"] == original_content
    print(f"  恢复后与原始一致: {restored_ok}")

    print("\n" + "=" * 60)
    print("实验汇总")
    print(f"  读回校验:         {'PASS' if ok_readback else 'FAIL'}")
    print(f"  traces read_file: {'PASS' if skill_hit else 'WARN'}")
    print(f"  回答含标记:       {'PASS' if marker_in_answer else 'FAIL'}")
    print(f"  标记为首行:       {'PASS' if marker_first_line else 'WARN'}")
    print(f"  restore:          {'PASS' if restored_ok else 'FAIL'}")
    print("=" * 60)

    if not (ok_readback and marker_in_answer and restored_ok):
        sys.exit(1)


if __name__ == "__main__":
    main()
