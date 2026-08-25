from __future__ import annotations

from typing import Any, Dict, List, Tuple

DEFAULT_RESULT = {
    "pass": True,
    "reason": "",
    "suggestion": "",
}

SYSTEM_PROMPT = """# 角色
你是客服意图知识库的「相似问语义」质检专家。

# 职责边界
- 你只判断：这条相似问能否作为该意图的检索语料入库。
- 你不判断：隐私/违禁、与其他条目的重复或冲突（由其他规则处理）。

# 输入说明
- 相似问：用户可能说的自然语句。
- 标注意图名称 + 意图描述：该相似问应对应的业务意图；描述声明功能与业务范围。

# 判定流程（按顺序执行，任一步不通过 → pass=false）
1. **意图明确**：能识别具体业务诉求；寒暄、指代不明、无业务含义的短句 → 不通过。
2. **领域相关**：属于目标业务领域（如客服/售后场景）；闲聊或与业务无关 → 不通过。
3. **与标注意图一致**（核心）：用户诉求须落在「意图描述」覆盖范围内。
   - 明显属于其他业务（如问物流却标为退货）→ 不通过。
   - 意图描述为空时，仅依据「意图名称」判断业务范围。
4. **可复用语料**：宜为通用问法；强绑定具体人名、卡号、精确金额 → 不通过。
5. **容错**：错别字、同音字（如「退貨/退货」「订単/订单」）不影响通过；业务诉求明确且与标注意图一致即可。

# 输出格式
严格输出单个 JSON 对象（不要 Markdown 代码块）：
{"pass": true/false, "reason": "...", "suggestion": "..."}

| pass | reason | suggestion |
|------|--------|------------|
| true | 空字符串 | 空字符串 |
| false | 一句话说明原因（中文） | 改写后的相似问（不改意图名称） |

布尔值使用小写 true/false。"""

USER_TEMPLATE = """请按判定流程质检下列语料，输出 JSON。

【语料】
相似问：{question}
标注意图名称：{intent_name}
意图描述：{intent_description}"""

FEW_SHOT: List[Tuple[str, str]] = [
    (
        "请按判定流程质检下列语料，输出 JSON。\n\n【语料】\n相似问：我要退货\n标注意图名称：退货申请\n意图描述：支持用户对已购商品发起退货",
        '{"pass":true,"reason":"","suggestion":""}',
    ),
    (
        "请按判定流程质检下列语料，输出 JSON。\n\n【语料】\n相似问：好的\n标注意图名称：理财推荐\n意图描述：提供基金、理财等产品申购入口",
        '{"pass":false,"reason":"相似问过于模糊，无法表达明确业务意图","suggestion":"有哪些适合我的理财产品"}',
    ),
    (
        "请按判定流程质检下列语料，输出 JSON。\n\n【语料】\n相似问：订单到哪了\n标注意图名称：退货申请\n意图描述：支持用户对已购商品发起退货",
        '{"pass":false,"reason":"问法为物流查询，超出该意图描述所覆盖的退货业务范围","suggestion":"怎么申请退货"}',
    ),
    (
        "请按判定流程质检下列语料，输出 JSON。\n\n【语料】\n相似问：我要退貨\n标注意图名称：退货申请\n意图描述：支持用户对已购商品发起退货",
        '{"pass":true,"reason":"","suggestion":""}',
    ),
]

SINGLE_USER_TEMPLATE = USER_TEMPLATE
FEW_SHOT_SINGLE = FEW_SHOT


def format_semantic_prompt(
    question: str, intent_name: str, intent_description: str = ""
) -> str:
    desc = (intent_description or "").strip() or "（未提供，仅依据意图名称判断业务范围）"
    return USER_TEMPLATE.format(
        question=question,
        intent_name=intent_name,
        intent_description=desc,
    )


def build_openai_messages(user_prompt: str) -> List[dict]:
    messages: List[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]
    for user_ex, assistant_ex in FEW_SHOT:
        messages.append({"role": "user", "content": user_ex})
        messages.append({"role": "assistant", "content": assistant_ex})
    messages.append({"role": "user", "content": user_prompt})
    return messages


def build_http_question(user_prompt: str) -> str:
    parts = [SYSTEM_PROMPT, ""]
    for user_ex, assistant_ex in FEW_SHOT:
        parts.append(f"【示例】\n用户：{user_ex}\n助手：{assistant_ex}\n")
    parts.append("【当前任务】\n" + user_prompt)
    return "\n".join(parts)


def normalize_result(row: Dict[str, Any]) -> Dict[str, Any]:
    if "pass" in row:
        passed = bool(row.get("pass", True))
    elif "valid" in row or "vague" in row:
        passed = bool(row.get("valid", True)) and not bool(row.get("vague", False))
    else:
        passed = True
    reason = str(row.get("reason", "") or "").strip()
    suggestion = str(row.get("suggestion", "") or "").strip()
    if passed:
        return dict(DEFAULT_RESULT)
    if not reason:
        reason = "语义质量不足：相似问超出意图描述覆盖的业务范围、过于模糊或与标注意图不匹配"
    if not suggestion:
        suggestion = "请改写为落在该意图描述功能范围内的具体业务问法"
    return {"pass": False, "reason": reason, "suggestion": suggestion}
