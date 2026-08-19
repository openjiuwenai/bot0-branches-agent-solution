from __future__ import annotations

from typing import Any, Dict, List, Tuple

DUP_CONFLICT_DEFAULT = {"confirmed": False, "reason": ""}
DUP_CONFLICT_BATCH_DEFAULT: Dict[str, Any] = {"judgments": []}

RELATION_DUPLICATE = "duplicate"
RELATION_CONFLICT = "conflict"

SYSTEM = """# 角色
你是客服意图知识库的「重复 / 冲突」二次裁决专家。

# 背景
向量检索已筛出高相似候选。你的任务是：逐条判断候选与「当前语料」是否构成真实的重复或冲突。
向量相似度高 ≠ 必然重复或冲突；须结合业务语义与标注意图独立裁决。

# 概念
| 类型 | 前提 | 成立标准 |
|------|------|----------|
| 重复 | 标注意图相同 | 两条相似问语义等价或高度重叠，合并为一条即可 |
| 冲突 | 标注意图不同 | 用户诉求语义相近，在实际对话中易被误判到错误意图，需人工裁定 |

# 裁决流程（对每条候选独立执行）
## 当关系类型为「重复」时
1. 标注意图是否相同？不同 → confirmed=false。
2. 用户诉求是否等价或高度重叠（表述不同但问的是同一件事）？是 → confirmed=true。
3. 是否一条为办理、另一条为规则/限额/条件咨询？是 → confirmed=false（不宜合并）。

## 当关系类型为「冲突」时
1. 标注意图是否不同？相同 → confirmed=false（属重复检测范畴）。
2. 用户真实诉求是否语义相近？否 → confirmed=false。
3. 在实际对话中是否容易判错意图？是 → confirmed=true；业务可明确区分且标注合理 → confirmed=false。

# 常见误判（应判 confirmed=false）
- 字面相近但业务不同：订单进度 vs 物流明细、手机号 vs 密码、绑定 vs 解绑。
- 结构相似但各自标注意图与诉求一致、不会混淆。

# 输出格式
严格输出 JSON（不要 Markdown 代码块）：
{"judgments": [{"index": 1, "confirmed": true/false, "reason": "..."}, ...]}

- index：与候选列表编号一致（从 1 开始），须覆盖每一条。
- confirmed=true：重复/冲突成立；false：不成立。
- reason：须简要中文说明裁决依据；confirmed=true 时说明为何成立，false 时说明为何不成立。
- 布尔值使用小写 true/false。"""

USER_TEMPLATE = """关系类型：{relation_label}

【当前语料】
相似问：{question}
标注意图：{intent_name}

【候选列表】共 {count} 条（请按裁决流程逐条独立判断）
{hits_text}

输出 judgments 数组。"""

FEW_SHOT_BY_RELATION: Dict[str, List[Tuple[str, str]]] = {
    RELATION_DUPLICATE: [
        (
            """关系类型：重复

【当前语料】
相似问：我要退货
标注意图：退货申请

【候选列表】共 2 条（请按裁决流程逐条独立判断）
1. 相似度 0.95 | 相似问：我想把商品退掉 | 标注意图：退货申请
2. 相似度 0.88 | 相似问：退货运费谁承担 | 标注意图：退货申请

输出 judgments 数组。""",
            '{"judgments":[{"index":1,"confirmed":true,"reason":""},{"index":2,"confirmed":false,"reason":"一条是发起退货、一条是咨询运费规则，语义不等价"}]}',
        ),
    ],
    RELATION_CONFLICT: [
        (
            """关系类型：冲突

【当前语料】
相似问：订单到哪了
标注意图：物流查询

【候选列表】共 2 条（请按裁决流程逐条独立判断）
1. 相似度 0.93 | 相似问：怎么看购买记录 | 标注意图：历史订单
2. 相似度 0.87 | 相似问：最近的门店在哪 | 标注意图：门店查询

输出 judgments 数组。""",
            '{"judgments":[{"index":1,"confirmed":false,"reason":"分别为查物流与查历史订单，业务不同"},{"index":2,"confirmed":false,"reason":"查物流与找门店业务不同"}]}',
        ),
        (
            """关系类型：冲突

【当前语料】
相似问：附近哪里有自提点
标注意图：自提点查询

【候选列表】共 1 条（请按裁决流程逐条独立判断）
1. 相似度 0.87 | 相似问：最近的门店在哪 | 标注意图：门店查询

输出 judgments 数组。""",
            '{"judgments":[{"index":1,"confirmed":true,"reason":"均可能在找线下服务渠道，易混淆"}]}',
        ),
    ],
}


def few_shot_for_relation(relation: str) -> List[Tuple[str, str]]:
    return list(FEW_SHOT_BY_RELATION.get(relation, []))


def _format_hits_text(hits: List[Dict[str, Any]]) -> str:
    lines: List[str] = []
    for i, hit in enumerate(hits, 1):
        lines.append(
            f"{i}. 相似度 {float(hit.get('similarity', 0)):.2f} | "
            f"相似问：{hit.get('document') or ''} | "
            f"标注意图：{hit.get('intent_name') or ''}"
        )
    return "\n".join(lines)


def format_dup_conflict_batch_prompt(
    question: str,
    intent_name: str,
    hits: List[Dict[str, Any]],
    relation: str,
) -> str:
    label = "重复" if relation == RELATION_DUPLICATE else "冲突"
    return USER_TEMPLATE.format(
        relation_label=label,
        question=question,
        intent_name=intent_name,
        count=len(hits),
        hits_text=_format_hits_text(hits),
    )


def build_dup_conflict_openai_messages(
    user_prompt: str, relation: str = RELATION_DUPLICATE
) -> List[dict]:
    messages: List[dict] = [{"role": "system", "content": SYSTEM}]
    for user_ex, assistant_ex in few_shot_for_relation(relation):
        messages.append({"role": "user", "content": user_ex})
        messages.append({"role": "assistant", "content": assistant_ex})
    messages.append({"role": "user", "content": user_prompt})
    return messages


def build_dup_conflict_http_question(
    user_prompt: str, relation: str = RELATION_DUPLICATE
) -> str:
    parts = [SYSTEM, ""]
    for user_ex, assistant_ex in few_shot_for_relation(relation):
        parts.append(f"【示例】\n用户：{user_ex}\n助手：{assistant_ex}\n")
    parts.append("【当前任务】\n" + user_prompt)
    return "\n".join(parts)


def normalize_dup_conflict_result(
    row: Dict[str, Any], relation: str = RELATION_DUPLICATE
) -> Dict[str, Any]:
    confirmed = bool(row.get("confirmed", False))
    reason = str(row.get("reason", "") or "").strip()
    if confirmed and not reason:
        if relation == RELATION_CONFLICT:
            reason = "用户诉求语义相近，易混淆至错误意图"
        else:
            reason = "两条相似问语义等价或高度重叠"
    return {"confirmed": confirmed, "reason": reason}


def normalize_dup_conflict_batch_result(
    row: Dict[str, Any], hit_count: int, relation: str = RELATION_DUPLICATE
) -> Dict[str, Any]:
    aligned: List[Dict[str, Any]] = [
        dict(DUP_CONFLICT_DEFAULT) for _ in range(hit_count)
    ]
    raw = row.get("judgments")
    if not isinstance(raw, list):
        return {"judgments": aligned}
    for item in raw:
        if not isinstance(item, dict):
            continue
        try:
            idx = int(item.get("index", 0))
        except (TypeError, ValueError):
            continue
        if 1 <= idx <= hit_count:
            aligned[idx - 1] = normalize_dup_conflict_result(item, relation)
    return {"judgments": aligned}
