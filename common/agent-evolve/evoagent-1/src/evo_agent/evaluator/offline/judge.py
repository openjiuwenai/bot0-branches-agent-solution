"""llm_judge 阶段——并发调 LLM 把每条 pred 分类到一个用户声明的标签。

独立模块（``pipeline`` 编排时调用）：LLM 调用是 async + 需并发，不能塞进 sync 的
``Metric.compute``。本模块负责拼 prompt、解析 LLM 输出的标签、并发执行、进度推送；
judged label 以 side-channel（``{materialized_index: str}``）交回 pipeline 注入
materialized 与 per-case 指标。

judge 是**分类器**而非「判相等」：LLM 从 ``group.labels`` 里选一个规范标签（**不读
gold**——防泄漏），scorer 用 ``(gold, judged_label)`` 建真实多类混淆矩阵。pred 归不
进任一声明标签 → 保留词 ``"其他"``（诊断桶，不进 macro）。

单条 LLM 异常或响应无法解析 → ``"其他"`` + ``logger.warning``，不阻断整批（仿
``extractor`` 降级哲学）。不区分 ICBC token 错误，保持与 provider 解耦。
"""

from __future__ import annotations

import asyncio
import logging

from openjiuwen.core.foundation.llm import Model, UserMessage

from evo_agent.api.jobs import Job
from evo_agent.evaluator.offline.models import GroupConfig, MaterializedCase

logger = logging.getLogger(__name__)

__all__ = ["judge", "build_judge_prompt", "parse_label"]

# 进度推送粒度——每处理多少条推一次事件，避免事件风暴。
_PROGRESS_EVERY = 10

# llm_judge 阶段 LLM 并发上限。
_JUDGE_CONCURRENCY = 8

# judged_label 归不进任一声明标签时的诊断保留词（macro P/R/F1 排除它）。
_OTHER_LABEL = "其他"

# 默认判定 prompt——支持 {extract_key}/{pred}/{labels} 占位符；固定模板，无自定义 prompt。
# {extract_key} 是需从 pred 中提取的内容（如「是否属实」），LLM 凭对该内容的理解把 pred
# 归进 labels；不暴露 {gold}（judge 是分类器，不读 gold，防泄漏）。
_DEFAULT_JUDGE_PROMPT = (
    "依据业务理解，从预测中提取「{extract_key}」的取值，只能从下列标签中选一个："
    "{labels}\n预测：{pred}\n只输出标签本身，不要解释。"
)


def _normalize_label(text: str) -> str:
    """归一化：strip + 去尾标点（ASCII 与 CJK）+ 拉丁文小写。

    用于 ``parse_label`` 的精确等值与子串包含比较——容错啰嗦 LLM 回「答案是：否。」。
    """
    s = text.strip()
    while s and s[-1] in "。.，,！!？?；;：:":
        s = s[:-1].rstrip()
    return s.lower()


def build_judge_prompt(group: GroupConfig, pred: str, labels: tuple[str, ...]) -> str:
    """组装单条判定 prompt：渲染固定模板的 ``{extract_key}``/``{pred}``/``{labels}``。

    固定模板（无自定义 prompt）——``extract_key`` 是需从 pred 中提取的内容（取
    ``group.extract_key``），LLM 凭对该内容的理解把 pred 归进 ``labels``；``labels`` 渲染为
    ``", ".join(labels)``。**不暴露 ``{gold}``**——judge 是分类器，不读 gold（防泄漏）。
    """
    labels_str = ", ".join(labels)
    return (
        _DEFAULT_JUDGE_PROMPT
        .replace("{extract_key}", group.extract_key)
        .replace("{pred}", pred)
        .replace("{labels}", labels_str)
    )


def parse_label(content: str, labels: tuple[str, ...]) -> str:
    """把 LLM 响应文本解析为一个规范标签；归不进任一声明标签 → ``"其他"``。

    解析顺序（确定性，声明顺序优先以消歧）：
    1. 归一化精确等值（``"否。"`` == ``"否"``）；
    2. 归一化后的响应**包含**某声明标签子串（声明序优先，长标签先于短标签靠声明序兜底）；
    3. 仍无 → ``"其他"``。

    纯精确匹配对啰嗦 LLM（回「答案是：否」）会假错，故必须容错到子串包含。
    """
    if not labels:
        return _OTHER_LABEL
    norm = _normalize_label(content)
    if not norm:
        return _OTHER_LABEL
    # 1. 精确等值
    for lab in labels:
        if norm == _normalize_label(lab):
            return lab
    # 2. 子串包含（声明序优先）
    for lab in labels:
        if _normalize_label(lab) in norm:
            return lab
    return _OTHER_LABEL


async def judge(
    job: Job,
    materialized: list[MaterializedCase],
    groups: list[GroupConfig],
    model: Model | None,
) -> dict[int, str]:
    """llm_judge 阶段：并发调 LLM 把每条 pred 分类到一个 ``group.labels`` 标签。

    只处理 ``llm_judge`` 组的 case，返回 ``{materialized_index: judged_label}``。
    judged_label ∈ ``group.labels`` 或保留词 ``"其他"``；LLM 异常或响应无法解析 →
    ``"其他"`` + ``logger.warning``，不阻断整批。无 llm_judge 组或无 model 时返回空 dict。
    """
    judge_groups = {g.name: g for g in groups if g.kind == "llm_judge"}
    if not judge_groups or model is None:
        return {}

    targets: list[tuple[int, MaterializedCase]] = [
        (i, mc) for i, mc in enumerate(materialized) if mc.group in judge_groups
    ]
    total = len(targets)
    job.push_event("progress", {"phase": "judge", "done": 0, "total": total})
    if total == 0:
        return {}

    sem = asyncio.Semaphore(_JUDGE_CONCURRENCY)
    judged_labels: dict[int, str] = {}
    done = 0

    async def _one(index: int, mc: MaterializedCase) -> None:
        async with sem:
            group = judge_groups[mc.group]
            pred = "" if mc.extracted is None else str(mc.extracted)
            prompt = build_judge_prompt(group, pred, group.labels)
            try:
                response = await model.invoke([UserMessage(content=prompt)])
                content = str(response.content)
            except Exception as e:  # noqa: BLE001 — 单条失败降级，不阻断整批
                logger.warning("llm_judge LLM call failed (case %s): %s", mc.case_id, e)
                judged_labels[index] = _OTHER_LABEL
                return
            label = parse_label(content, group.labels)
            if label == _OTHER_LABEL:
                logger.warning(
                    "llm_judge unparseable label (case %s): %r", mc.case_id, content[:80]
                )
            judged_labels[index] = label

    tasks = [asyncio.create_task(_one(i, mc)) for i, mc in targets]
    for coro in asyncio.as_completed(tasks):
        await coro
        done += 1
        if done % _PROGRESS_EVERY == 0 or done == total:
            job.push_event("progress", {"phase": "judge", "done": done, "total": total})
    return judged_labels


# pipeline 内部别名（保持旧调用点 ``_judge``/``_build_judge_prompt``/``_parse_label``
# 可用——单测经此重导出导入，见 ``test_pipeline``）。
_judge = judge
_build_judge_prompt = build_judge_prompt
_parse_label = parse_label
