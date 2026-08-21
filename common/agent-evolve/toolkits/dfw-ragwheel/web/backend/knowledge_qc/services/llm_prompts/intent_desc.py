from __future__ import annotations

import re
from typing import Any, Dict, List, Tuple

INTENT_DESC_DEFAULT = {"pass": True, "reason": ""}

SYSTEM = """# 角色
你是客服意图知识库的「意图描述」质检专家。

# 职责边界
- 你判断：新的「意图名称 + 意图描述」是否适合作为独立意图入库。
- 依据：向量检索到的已有相似描述（本批语料或库内语料）。

# 判定流程
对每条相似候选，检查新语料与其是否存在下列关系（任一命中 → pass=false）：

1. **高度重复**：描述语义雷同，可互相替代，保留一条即可。
2. **包含关系**：一方业务范围完全涵盖另一方（新⊂旧 或 新⊃旧）。
   - 例：「退货申请」与「仅退款」；「修改个人信息」与「修改手机号」。
3. **交叉关系**：业务范围部分重叠、边界不清，同一诉求可能同时命中两个意图。
   - 例：「订单查询」与「物流跟踪」；「会员充值」与「自动续费」。

pass=true 仅当：与所有候选均不构成上述 1～3 种关系，业务边界清晰、可并存。

# 输出格式
严格输出 JSON（不要 Markdown 代码块）：
{"pass": true/false, "reason": "..."}

| pass | reason |
|------|--------|
| true | 空字符串 |
| false | 中文说明，须逐条引用命中的候选（见下方格式） |

## reason 引用格式（不通过时必遵守）
- **本批语料**：「与本批第 N 行意图「意图名称」存在…关系（简要理由）」—— N 为候选标注的 Excel 行号。
- **库内语料**：「与库内意图「意图名称」存在…关系（简要理由）」—— **禁止**写「库内第 N 行/条」或候选编号。
- 多条命中用分号分隔；须写明关系类型（高度重复 / 包含 / 交叉）。
- **禁止**写「与本批第{当前行}行」存在问题（当前行是新语料自身）。

示例：
新意图「仅退款」不宜单独入库：与本批第1行意图「退货申请」存在包含关系（仅退款属于退货售后的子业务）；与库内意图「取消订单」存在交叉关系（业务范围重叠）。"""

USER_TEMPLATE = """【新语料】Excel 第 {row_index} 行
意图名称：{intent_name}
意图描述：{description}

【相似候选】共 {top_n} 条（已排除本行自身）
{hits_text}

按判定流程输出 JSON：pass、reason。"""

INTENT_DESC_SYSTEM = SYSTEM
INTENT_DESC_USER_TEMPLATE = USER_TEMPLATE

FEW_SHOT: List[Tuple[str, str]] = [
    (
        """【新语料】Excel 第 3 行
意图名称：仅退款
意图描述：支持用户对未发货或已收货订单申请仅退款

【相似候选】共 1 条（已排除本行自身）
候选1 [本批] Excel 第1行，意图「退货申请」，相似度=0.82
   引用时写：与本批第1行意图「退货申请」
   描述：支持用户对已购商品发起退货

按判定流程输出 JSON：pass、reason。""",
        '{"pass":false,"reason":"与本批第1行意图「退货申请」存在包含关系（仅退款属于退货售后的子业务）"}',
    ),
]


def intent_hit_scope_label(corpus_scope: str) -> str:
    return "本批" if corpus_scope == "batch" else "库内"


def sanitize_intent_desc_reason(reason: str) -> str:
    """修正 LLM 误将库内候选序号写成「库内第 N 行」等表述。"""
    if not reason:
        return reason
    text = reason.strip()
    text = re.sub(
        r"(与)?库内第\s*\d+\s*行(?:\s*意图)?\s*",
        lambda m: ("与" if m.group(1) else "") + "库内意图",
        text,
    )
    text = re.sub(r"库内第\s*\d+\s*条(?:\s*意图)?\s*", "库内意图", text)
    text = re.sub(
        r"与本批第\s*(\d+)\s*行\s*(?!意图)([「\"])",
        r"与本批第\1行意图\2",
        text,
    )
    return text


def format_intent_desc_prompt(
    intent_name: str,
    description: str,
    hits: List[Dict[str, Any]],
    row_index: int = 0,
) -> str:
    lines = []
    for i, h in enumerate(hits, 1):
        scope = intent_hit_scope_label(h.get("corpus_scope", ""))
        name = (h.get("intent_name") or "").strip() or "（未命名）"
        if h.get("corpus_scope") == "batch" and h.get("row_index"):
            locate = f"Excel 第{h['row_index']}行"
            cite_hint = f"引用时写：与本批第{h['row_index']}行意图「{name}」"
        else:
            locate = "库内（无行号）"
            cite_hint = f"引用时写：与库内意图「{name}」"
        lines.append(
            f"候选{i} [{scope}] {locate}，意图「{name}」，相似度={h.get('similarity', 0):.2f}\n"
            f"   {cite_hint}\n"
            f"   描述：{h.get('intent_description') or h.get('document', '')}"
        )
    hits_text = "\n".join(lines) if lines else "（无相似候选）"
    return USER_TEMPLATE.format(
        intent_name=intent_name,
        description=description,
        row_index=row_index,
        top_n=len(hits),
        hits_text=hits_text,
    )


def build_intent_desc_openai_messages(user_prompt: str) -> List[dict]:
    messages: List[dict] = [{"role": "system", "content": SYSTEM}]
    for user_ex, assistant_ex in FEW_SHOT:
        messages.append({"role": "user", "content": user_ex})
        messages.append({"role": "assistant", "content": assistant_ex})
    messages.append({"role": "user", "content": user_prompt})
    return messages


def build_intent_desc_http_question(user_prompt: str) -> str:
    parts = [SYSTEM, ""]
    for user_ex, assistant_ex in FEW_SHOT:
        parts.append(f"【示例】\n用户：{user_ex}\n助手：{assistant_ex}\n")
    parts.append("【当前任务】\n" + user_prompt)
    return "\n".join(parts)


def normalize_intent_desc_result(row: Dict[str, Any]) -> Dict[str, Any]:
    passed = bool(row.get("pass", True))
    if passed:
        return dict(INTENT_DESC_DEFAULT)
    reason = sanitize_intent_desc_reason(str(row.get("reason", "") or "").strip())
    if not reason:
        reason = (
            "意图描述与已有语料存在高度重复、包含或业务范围交叉，不宜单独入库"
        )
    return {"pass": False, "reason": reason}
