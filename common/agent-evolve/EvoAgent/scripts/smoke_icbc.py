#!/usr/bin/env python
"""ICBC 内网 provider 冒烟脚本（T6） — 真实流式端点 gate，需客户 token。

从环境变量读取 EVO_ICBC_* 凭证，对真实 ICBC chat/completions 流式端点发请求，
断言通过才 exit 0；任一失败 exit 非 0 + 诊断。本地无 token 时 skipped exit 0，不阻塞 CI。

3 项 gate（见 docs/adr/0008-icbc-endpoint-openai-streaming.md）：
  1. JSON 保真：要求只输出 JSON → invoke 累加后 content 可解析为 JSON。
  2. 真实评估 prompt 可解析：用 policy_v1 DEFAULT_PROMPT_TEMPLATE → invoke 累加后
     content 可被 LLMEvaluationOutput 解析。
  3. 流式 chunk + [DONE]：stream() 收到 ≥1 个 content chunk 且正常结束
     （收到 [DONE] 或连接自然关闭）。

用法:
    export EVO_ICBC_TOKEN=<JWT>
    export EVO_ICBC_USER_ID=<固定 userId>
    export EVO_ICBC_ENDPOINT=http://aigc.sdc.cs.icbc/mlpmodelservice/aigc/chat/completions
    uv run python scripts/smoke_icbc.py
"""

from __future__ import annotations

import asyncio
import os
import sys
import traceback

# 确保 src 在 path（uv run 时已装包，但脚本直接跑也兼容）
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from openjiuwen.core.foundation.llm.model_clients import create_model_client  # noqa: E402
from openjiuwen.core.foundation.llm.schema.config import (  # noqa: E402
    ModelClientConfig,
    ModelRequestConfig,
)
from openjiuwen.core.foundation.llm.schema.message import UserMessage  # noqa: E402

# import evo_agent 触发 registry 注册（client_provider="ICBC" 校验依赖）
import evo_agent  # noqa: E402,F401
from evo_agent.evaluator.domain.models import LLMEvaluationOutput  # noqa: E402
from evo_agent.evaluator.evaluators.llm import _extract_json  # noqa: E402
from evo_agent.evaluator.prompts.policy_v1 import DEFAULT_PROMPT_TEMPLATE  # noqa: E402
from evo_agent.llm.icbc_model_client import (  # noqa: E402
    ICBCModelClient,
    ICBCRequestError,
    ICBCTokenExpiredError,
)


def _build_client(endpoint: str, token: str, user_id: str) -> object:
    return create_model_client(
        ModelClientConfig(
            client_provider="ICBC",
            api_key=token,
            api_base=endpoint,
            user_id=user_id,
            verify_ssl=False,
        ),
        ModelRequestConfig(model_name="icbc-deepseek"),
    )


async def _check_1_json_fidelity(client: ICBCModelClient) -> None:
    """JSON 保真：要求只输出 JSON，invoke 累加后 content 可解析。"""
    question = '只输出 JSON，不要任何额外文字：{"score":0.5}'
    msg = await client.invoke([UserMessage(content=question)])
    data = _extract_json(msg.content)
    assert data is not None, f"content 无法解析为 JSON: {msg.content!r}"
    assert data.get("score") == 0.5, f"score 字段不符: {data}"


async def _check_2_real_eval_prompt_parseable(client: ICBCModelClient) -> None:
    """真实评估 prompt：policy_v1 模板 → invoke 累加后 content 可被 LLMEvaluationOutput 解析。"""
    prompt = DEFAULT_PROMPT_TEMPLATE.format(
        expected_section="预期：用户问天气，agent 应回复北京今日晴。",
        skill_names_section="",
        diagnostic_rules="",
        skill_names="[]",
        messages='[{"role":"user","content":"北京今天天气怎么样？"},{"role":"assistant","content":"北京今日晴。"}]',
    )
    msg = await client.invoke([UserMessage(content=prompt)])
    data = _extract_json(msg.content)
    assert data is not None, f"评估输出无法解析为 JSON: {msg.content[:200]!r}"
    parsed = LLMEvaluationOutput.model_validate(data)
    assert 0.0 <= parsed.score <= 1.0, f"score 越界: {parsed.score}"


async def _check_3_stream_chunks_and_done(client: ICBCModelClient) -> None:
    """流式：stream() 收到 ≥1 个 content chunk 且正常结束（[DONE] / 连接关闭）。"""
    chunks: list[str] = []
    async for chunk in client.stream([UserMessage(content="请从 1 数到 5")]):
        chunks.append(chunk.content)
    assert chunks, "stream 未收到任何 content chunk"
    joined = "".join(chunks).strip()
    assert joined, f"stream 累加内容为空: {chunks!r}"


_CHECKS = (
    ("JSON 保真", _check_1_json_fidelity),
    ("真实评估 prompt 可解析", _check_2_real_eval_prompt_parseable),
    ("流式 chunk + [DONE]", _check_3_stream_chunks_and_done),
)


async def run_smoke(token: str, user_id: str, endpoint: str) -> int:
    client = _build_client(endpoint, token, user_id)  # type: ignore[assignment]
    assert isinstance(client, ICBCModelClient), f"client 类型异常: {type(client)}"
    failures: list[str] = []
    for name, check in _CHECKS:
        try:
            await check(client)
            print(f"[PASS] {name}")
        except (ICBCTokenExpiredError, ICBCRequestError) as exc:
            failures.append(f"{name}: 端点错误 {type(exc).__name__}: {exc}")
            print(f"[FAIL] {name}: {type(exc).__name__}: {exc}")
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{name}: {type(exc).__name__}: {exc}")
            print(f"[FAIL] {name}: {type(exc).__name__}: {exc}")
            traceback.print_exc()
    if failures:
        print(f"\n冒烟失败 {len(failures)}/{len(_CHECKS)}：")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"\n冒烟全过 {len(_CHECKS)}/{len(_CHECKS)}")
    return 0


def main() -> int:
    token = os.environ.get("EVO_ICBC_TOKEN", "").strip()
    user_id = os.environ.get("EVO_ICBC_USER_ID", "").strip()
    endpoint = os.environ.get("EVO_ICBC_ENDPOINT", "").strip()
    if not token or not user_id or not endpoint:
        print("no token, skipped（EVO_ICBC_TOKEN/USER_ID/ENDPOINT 未设置）")
        return 0
    print(f"冒烟目标: {endpoint} (user={user_id})")
    return asyncio.run(run_smoke(token, user_id, endpoint))


if __name__ == "__main__":
    raise SystemExit(main())
