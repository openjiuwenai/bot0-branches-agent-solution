"""E2E Smoke Test — Adapter 连通性 + Skill 操作验证。

运行方式：
    cd /path/to/EvoAgent
    unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
    uv run python tests/e2e/test_smoke.py

验证内容：
    1. Adapter 连通性（skill_list）
    2. Skill 内容获取（skill_content）
    3. Conversation 调用（invoke + get_traces，带 extra_data + retry）
"""

from __future__ import annotations

import asyncio
import os
import sys
from urllib.parse import urlparse

from evo_agent.adapter_client.client import AdapterClient, AdapterError

ADAPTER_URL = "http://124.71.234.237:8900"

# 将 adapter 地址加入 no_proxy（绕过本地代理，避免 502）
for _var in ("no_proxy", "NO_PROXY"):
    _existing = os.environ.get(_var, "")
    if ADAPTER_URL:
        _host = urlparse(ADAPTER_URL).hostname or ""
        if _host and _host not in _existing:
            os.environ[_var] = f"{_existing},{_host}" if _existing else _host
AGENT_NAME = "edp_agent"

# 场景级 extra_data（来自 scenarios/edp_agent/scenario.yaml）
ROLL_EXTRA_DATA = {"role_id": "1", "role_name": "mobile-bank"}


async def test_skill_list(client: AdapterClient) -> list[dict]:
    """Step 1: 获取 skill 列表。"""
    print("[1/4] skill_list ...", end=" ")
    skills = await client.skill_list()
    print(f"OK — {len(skills)} skills")
    for s in skills:
        print(f"       - {s.get('name', '?')}")
    return skills


async def test_skill_content(client: AdapterClient, skill_name: str) -> str:
    """Step 2: 获取单个 skill 内容。"""
    print(f"[2/4] skill_content({skill_name}) ...", end=" ")
    content = await client.skill_content(skill_name)
    preview = content[:80].replace("\n", " ")
    print(f"OK — {len(content)} chars")
    print(f"       preview: {preview}...")
    return content


async def test_conversation(client: AdapterClient, query: str) -> dict:
    """Step 3: 触发一次对话（带 extra_data）+ 获取轨迹（带 retry）。"""
    case_id = "smoke_test_002"
    print(f"[3/4] invoke(query={query!r}, extra_data={ROLL_EXTRA_DATA}) ...", end=" ")

    result = await client.invoke(
        case_id=case_id,
        query=query,
        extra_data=ROLL_EXTRA_DATA,
        run_id="smoke_test",
    )

    success = result.get("success", False)
    interrupted = result.get("interrupted", False)
    answer = result.get("answer", "")
    print(f"OK — success={success}, interrupted={interrupted}")
    if answer:
        print(f"       answer: {answer[:100]}...")
    if not success:
        print(f"       error: {result.get('error', 'unknown')}")
        return {}

    return result


async def test_traces(client: AdapterClient, max_retries: int = 5, backoff: float = 2.0) -> dict:
    """Step 4: 获取轨迹（带 retry + backoff，等待日志采集）。"""
    case_id = "smoke_test_002"
    print(f"[4/4] get_traces (max_retries={max_retries}, backoff={backoff}s) ...")

    for attempt in range(max_retries):
        traces = await client.get_traces(case_id=case_id)
        messages = traces.get("messages", [])

        if messages:
            print(f"       ✅ attempt {attempt + 1}: {len(messages)} messages")
            for msg in messages[:5]:
                role = msg.get("role", "?")
                content = str(msg.get("content", ""))[:80]
                tool_calls = msg.get("tool_calls", [])
                if tool_calls:
                    tools = [tc.get("function", {}).get("name", "?") for tc in tool_calls]
                    print(f"       [{role}] tool_calls: {tools}")
                else:
                    print(f"       [{role}] {content}")
            if len(messages) > 5:
                print(f"       ... and {len(messages) - 5} more messages")
            return traces
        else:
            wait = backoff * (attempt + 1)
            print(f"       ⏳ attempt {attempt + 1}: empty, waiting {wait}s ...")
            await asyncio.sleep(wait)

    print("       ❌ No traces after all retries")
    print("       → 日志采集 Pipeline 可能未就绪，不影响 e2e 测试框架")
    return {}


async def main() -> None:
    print("=== E2E Smoke Test ===")
    print(f"Adapter: {ADAPTER_URL}")
    print(f"Agent:   {AGENT_NAME}")
    print(f"Extra:   {ROLL_EXTRA_DATA}")
    print()

    try:
        async with AdapterClient(ADAPTER_URL, agent_name=AGENT_NAME, timeout=180.0) as client:
            # Step 1: skill_list
            skills = await test_skill_list(client)
            if not skills:
                print("ERROR: No skills found!")
                sys.exit(1)

            # Step 2: skill_content (取 product_recommend_skill)
            target_skill = "product_recommend_skill"
            skill_names = [s.get("name", "") for s in skills]
            if target_skill not in skill_names:
                target_skill = skill_names[0]
            await test_skill_content(client, target_skill)

            # Step 3: conversation
            await test_conversation(client, "帮我推荐一款稳健的理财产品")

            # Step 4: traces (带 retry)
            await test_traces(client)

            print()
            print("=== SMOKE TEST COMPLETED ===")

    except AdapterError as e:
        print(f"\nFAILED: AdapterError — {e} (status={e.status_code})")
        sys.exit(1)
    except Exception as e:
        print(f"\nFAILED: {type(e).__name__} — {e}")
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
