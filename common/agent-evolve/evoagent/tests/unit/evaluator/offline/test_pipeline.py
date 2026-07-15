"""pipeline 单元测试（多组版）—— 记录解析（含 xlsx）、ingest、run_offline_eval、进度事件。"""

from __future__ import annotations

import asyncio
import io

import pytest

from evo_agent.api.jobs import JobStatus, job_manager
from evo_agent.evaluator.offline import (
    GroupConfig,
    OfflineEvalRequest,
    ingest,
    load_raw_records,
    run_offline_eval,
)
from evo_agent.evaluator.offline.judge import _build_judge_prompt, _judge, _parse_label

# ---------------------------------------------------------------------------
# load_raw_records
# ---------------------------------------------------------------------------


def test_load_json_array() -> None:
    data = b'[{"id":"1","gold":"x","pred":"y"},{"id":"2","gold":"x","pred":"y"}]'
    recs = load_raw_records(data, "items.json")
    assert len(recs) == 2
    assert recs[0]["gold"] == "x"


def test_load_json_single_object_wraps_to_list() -> None:
    recs = load_raw_records(b'{"id":"1","gold":"x","pred":"y"}', "x.json")
    assert recs == [{"id": "1", "gold": "x", "pred": "y"}]


def test_load_jsonl() -> None:
    data = b'{"id":"1","gold":"x","pred":"y"}\n{"id":"2","gold":"x","pred":"y"}\n'
    recs = load_raw_records(data, "items.jsonl")
    assert len(recs) == 2


def test_load_csv() -> None:
    data = b"id,gold,pred\n1,Paris,Paris\n2,London,Paris\n"
    recs = load_raw_records(data, "items.csv")
    assert recs[0]["gold"] == "Paris"
    assert recs[1]["pred"] == "Paris"


def _make_xlsx_bytes(rows: list[tuple[object, ...]]) -> bytes:
    """用 openpyxl 在内存构造一个单 sheet xlsx。首行视为表头。"""
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    for r in rows:
        ws.append(list(r))
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def test_load_xlsx() -> None:
    data = _make_xlsx_bytes(
        [("序号", "是否属实", "是否属实_pred"), (1, "否", "否"), (2, "否", "是")]
    )
    recs = load_raw_records(data, "数据集.xlsx")
    assert len(recs) == 2
    assert recs[0]["是否属实"] == "否"
    assert recs[1]["是否属实_pred"] == "是"


def test_load_empty_json_array_raises() -> None:
    with pytest.raises(ValueError):
        load_raw_records(b"[]", "x.json")


def test_load_empty_csv_raises() -> None:
    with pytest.raises(ValueError):
        load_raw_records(b"", "x.csv")


# ---------------------------------------------------------------------------
# ingest（多组）
# ---------------------------------------------------------------------------


def _em_group(name: str = "是否属实") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="exact_match",
        pred_field="pred",
        gold_field="gold",
    )


def _kw_group(name: str = "责任判定") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="keyword",
        pred_field="text",
        keywords=("属实", "供电公司责任"),
    )


def test_ingest_exact_match_group() -> None:
    records = [
        {"id": "a", "gold": "否", "pred": "否"},
        {"id": "b", "gold": "否", "pred": "是"},
    ]
    materialized = asyncio.run(ingest(records, [_em_group()], "id"))
    assert [m.case_id for m in materialized] == ["a", "b"]
    assert materialized[0].group == "是否属实"
    assert materialized[0].gold == "否"
    assert materialized[0].extracted == "否"
    assert materialized[0].extraction_method == "raw"


def test_ingest_keyword_group_derives_hits() -> None:
    records = [{"id": "1", "text": "该诉求属实"}, {"id": "2", "text": "无相关词"}]
    materialized = asyncio.run(ingest(records, [_kw_group()], "id"))
    assert materialized[0].extraction_method == "keyword"
    assert materialized[0].gold == ["属实", "供电公司责任"]
    assert materialized[0].extracted == ["属实"]  # 命中"属实"
    assert materialized[1].extracted == []  # 未中


def test_ingest_multi_group() -> None:
    """一条 record × 两组 → 两条物化，分别归属两组。"""
    records = [{"id": "1", "gold": "否", "pred": "否", "text": "属实"}]
    materialized = asyncio.run(ingest(records, [_em_group(), _kw_group()], "id"))
    assert len(materialized) == 2
    assert {m.group for m in materialized} == {"是否属实", "责任判定"}


def test_ingest_missing_id_field_falls_back_to_index() -> None:
    records = [{"gold": "g", "pred": "p"}]
    materialized = asyncio.run(ingest(records, [_em_group()], ""))
    assert materialized[0].case_id == "0"


def test_ingest_missing_gold_field_raises() -> None:
    records = [{"pred": "p"}]
    with pytest.raises(ValueError, match="gold_field"):
        asyncio.run(ingest(records, [_em_group()], "id"))


def test_ingest_missing_pred_field_raises() -> None:
    records = [{"gold": "g"}]
    with pytest.raises(ValueError, match="pred_field"):
        asyncio.run(ingest(records, [_em_group()], "id"))


def test_ingest_keyword_empty_keywords_raises() -> None:
    bad = GroupConfig(name="bad", kind="keyword", pred_field="text", keywords=())
    with pytest.raises(ValueError, match="keywords"):
        asyncio.run(ingest([{"text": "x"}], [bad], "id"))


def test_ingest_llm_judge_missing_extract_key_raises() -> None:
    """llm_judge 直连调用者缺 extract_key → ingest fail-fast（backstop，路由已 422）。"""
    bad = GroupConfig(
        name="g", kind="llm_judge", pred_field="p", gold_field="gold",
        labels=("否", "是"), extract_key="",
    )
    with pytest.raises(ValueError, match="extract_key"):
        asyncio.run(ingest([{"id": "1", "gold": "否", "p": "否"}], [bad], "id"))


# ---------------------------------------------------------------------------
# ingest（JSON pred 解析）
# ---------------------------------------------------------------------------


def test_ingest_json_key_extracts_per_group() -> None:
    """两组 pred 共存于同一 JSON 列 ``result``，各按不同 json_key 取值。"""
    records = [
        {
            "id": "1",
            "gold": "否",
            "gold2": "是",
            "result": '{"是否属实":"否","是否供电公司责任":"是"}',
        },
        {
            "id": "2",
            "gold": "是",
            "gold2": "否",
            "result": '{"是否属实":"是","是否供电公司责任":"否"}',
        },
    ]
    g1 = GroupConfig(
        name="是否属实",
        kind="exact_match",
        pred_field="result",
        gold_field="gold",
        json_key="是否属实",
    )
    g2 = GroupConfig(
        name="是否供电公司责任",
        kind="exact_match",
        pred_field="result",
        gold_field="gold2",
        json_key="是否供电公司责任",
    )
    materialized = asyncio.run(ingest(records, [g1, g2], "id"))
    assert len(materialized) == 4
    by = {(m.case_id, m.group): m for m in materialized}
    assert by[("1", "是否属实")].extracted == "否"
    assert by[("1", "是否供电公司责任")].extracted == "是"
    assert by[("2", "是否属实")].extracted == "是"
    assert all(m.extraction_method == "json" for m in materialized)


def test_ingest_json_key_missing_key_returns_none() -> None:
    """json_key 在某条 JSON 里不存在 → extracted=None（逐条宽松，交指标判 0）。"""
    records = [{"id": "1", "gold": "g", "result": '{"other": "x"}'}]
    g = GroupConfig(
        name="g", kind="exact_match", pred_field="result", gold_field="gold", json_key="是否属实"
    )
    m = asyncio.run(ingest(records, [g], "id"))[0]
    assert m.extracted is None
    assert m.extraction_method == "json"


def test_ingest_json_key_dotted_path() -> None:
    """json_key 支持 ``a.b`` 点号路径取嵌套值。"""
    records = [{"id": "1", "gold": "否", "result": '{"a": {"是否属实": "否"}}'}]
    g = GroupConfig(
        name="g", kind="exact_match", pred_field="result", gold_field="gold", json_key="a.是否属实"
    )
    m = asyncio.run(ingest(records, [g], "id"))[0]
    assert m.extracted == "否"


def test_ingest_json_key_not_json_raises() -> None:
    """json_key 设了但首记录 pred cell 非 JSON → fail-fast。"""
    records = [{"id": "1", "gold": "g", "result": "not json"}]
    g = GroupConfig(
        name="g", kind="exact_match", pred_field="result", gold_field="gold", json_key="是否属实"
    )
    with pytest.raises(ValueError, match="not valid JSON"):
        asyncio.run(ingest(records, [g], "id"))


def test_ingest_json_key_markdown_wrapped() -> None:
    """LLM 输出常把 JSON 塞在 ```json 围栏/散文里，解析器取 {..} 子串容忍。"""
    llm = '根据工单判断如下：\n```json\n{"是否属实":"否"}\n```'
    records = [{"id": "1", "gold": "否", "LLM输出": llm}]
    g = GroupConfig(
        name="是否属实",
        kind="exact_match",
        pred_field="LLM输出",
        gold_field="gold",
        json_key="是否属实",
    )
    m = asyncio.run(ingest(records, [g], "id"))[0]
    assert m.extracted == "否"
    assert m.extraction_method == "json"


def test_ingest_json_key_fence_precedence_over_braces() -> None:
    """散文里有杂散 {..} 且真 JSON 在 ```json 围栏里：围栏正则优先，{..} 兜底不被误伤。"""
    llm = '说明 {杂项} 不算\n```json\n{"是否属实":"否"}\n```'
    records = [{"id": "1", "gold": "否", "LLM输出": llm}]
    g = GroupConfig(
        name="是否属实",
        kind="exact_match",
        pred_field="LLM输出",
        gold_field="gold",
        json_key="是否属实",
    )
    m = asyncio.run(ingest(records, [g], "id"))[0]
    assert m.extracted == "否"  # 围栏内的值，非 {杂项}
    assert m.extraction_method == "json"


def test_ingest_json_key_keyword_group() -> None:
    """keyword 组 + json_key：pred 文本来自 JSON 解析值后再做关键词命中。"""
    records = [{"id": "1", "result": '{"判定":"该诉求不属实"}'}]
    g = GroupConfig(
        name="责任判定", kind="keyword", pred_field="result", keywords=("属实",), json_key="判定"
    )
    m = asyncio.run(ingest(records, [g], "id"))[0]
    assert m.extracted == ["属实"]
    assert m.extraction_method == "json_keyword"


# ---------------------------------------------------------------------------
# run_offline_eval
# ---------------------------------------------------------------------------


def _request(records: list[dict], dataset_id: str = "ds1") -> OfflineEvalRequest:
    return OfflineEvalRequest(
        dataset_id=dataset_id,
        raw_records=records,
        groups=[_em_group()],
        id_field="id",
    )


async def test_run_offline_eval_completes() -> None:
    records = [
        {"id": "1", "gold": "Paris", "pred": "Paris"},
        {"id": "2", "gold": "London", "pred": "Paris"},
    ]
    job = job_manager.submit({"dataset_id": "ds1"})
    await run_offline_eval(job, _request(records, "ds1"))
    assert job.status == JobStatus.COMPLETED
    assert job.result is not None
    assert job.result["extraction_summary"] == {"raw": 2}
    agg = job.result["aggregate"]["是否属实"]
    assert agg["exact_match"] == 0.5
    assert "_overall" in agg
    # 跨组 overall：_overall = 2 条 composite 均值 = 0.5；
    # 单组 exact_match(Paris/London,1对1错) macro P=0.25 R=0.5 F1=0.3333 Acc=0.5。
    ov = job.result["overall"]
    assert ov["_overall"] == 0.5
    assert ov["precision"] == pytest.approx(0.25)
    assert ov["recall"] == pytest.approx(0.5)
    assert ov["f1"] == pytest.approx(1 / 3)
    assert ov["accuracy"] == 0.5
    phases = [e.data.get("phase") for e in job.get_events_since(0) if e.event == "progress"]
    assert "ingest" in phases and "scoring" in phases and "aggregate" in phases


async def test_run_offline_eval_keyword_group() -> None:
    records = [{"id": "1", "text": "属实"}, {"id": "2", "text": "无"}]
    job = job_manager.submit({"dataset_id": "ds-kw"})
    request = OfflineEvalRequest(
        dataset_id="ds-kw",
        raw_records=records,
        groups=[_kw_group()],
        id_field="id",
    )
    await run_offline_eval(job, request)
    assert job.status == JobStatus.COMPLETED
    agg = job.result["aggregate"]["责任判定"]
    assert agg["keyword_hit"] == 0.5  # 命中率
    assert agg["precision"] == 1.0
    assert agg["recall"] == 0.5
    assert agg["f1"] == 2 * 0.5 / 1.5


async def test_run_offline_eval_failed_on_bad_field() -> None:
    records = [{"id": "1", "pred": "x"}]  # 缺 gold
    job = job_manager.submit({})
    await run_offline_eval(job, _request(records, "ds-bad"))
    assert job.status == JobStatus.FAILED
    assert job.error is not None and "gold_field" in job.error
    assert any(e.event == "error" for e in job.get_events_since(0))


async def test_summary_to_dict_shape() -> None:
    records = [{"id": "1", "gold": "Paris", "pred": "Paris"}]
    job = job_manager.submit({"dataset_id": "ds-shape"})
    await run_offline_eval(job, _request(records, "ds-shape"))
    assert job.status == JobStatus.COMPLETED
    result = job.result
    assert result is not None
    assert isinstance(result["per_case"], list)
    assert result["per_case"][0]["case_id"] == "1"
    assert "是否属实" in result["per_case"][0]["groups"]
    assert isinstance(result["aggregate"], dict)
    # 单条命中 → 单组单标签，overall 全 1.0（_overall + P/R/F1/Acc）
    ov = result["overall"]
    assert ov["_overall"] == 1.0
    assert ov["precision"] == 1.0 and ov["recall"] == 1.0
    assert ov["f1"] == 1.0 and ov["accuracy"] == 1.0
    assert isinstance(result["extraction_summary"], dict)


# ---------------------------------------------------------------------------
# llm_judge 阶段
# ---------------------------------------------------------------------------


def _lj_group(name: str = "是否属实") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="llm_judge",
        pred_field="pred",
        gold_field="gold",
        labels=("否", "是"),
        extract_key="是否属实",
    )


class _FakeAssistant:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeJudgeModel:
    """按预设序列依次返回 LLM 响应文本的假 Model。"""

    def __init__(self, contents: list[str]) -> None:
        self._contents = contents
        self._i = 0
        self.invoked = 0

    async def invoke(self, messages: object) -> _FakeAssistant:  # noqa: ARG002
        content = self._contents[self._i % len(self._contents)]
        self._i += 1
        self.invoked += 1
        return _FakeAssistant(content)


def test_ingest_llm_judge_materializes_like_exact_match() -> None:
    """llm_judge 物化与 exact_match 同构：gold=gold_field、extracted=pred 原值、raw。"""
    records = [
        {"id": "a", "gold": "否", "pred": "否"},
        {"id": "b", "gold": "否", "pred": "不是"},
    ]
    materialized = asyncio.run(ingest(records, [_lj_group()], "id"))
    assert [m.gold for m in materialized] == ["否", "否"]
    assert [m.extracted for m in materialized] == ["否", "不是"]
    assert all(m.extraction_method == "raw" for m in materialized)


def test_parse_label_exact_substring_punct_other() -> None:
    """parse_label：精确/子串包含/去尾标点容错/其他/空。"""
    labels = ("否", "是")
    # 精确
    assert _parse_label("否", labels) == "否"
    assert _parse_label("是", labels) == "是"
    # 去尾标点容错（CJK + ASCII）
    assert _parse_label("否。", labels) == "否"
    assert _parse_label("否,", labels) == "否"
    # 啰嗦 LLM（回「答案是：否」）→ 子串包含命中
    assert _parse_label("答案是：否", labels) == "否"
    # 声明序优先消歧：labels=("否","是")，响应含「否」先命中「否」
    assert _parse_label("回答为否", labels) == "否"
    # 归不进任一声明标签 → 其他
    assert _parse_label("无法判断", labels) == "其他"
    # 空 labels → 其他
    assert _parse_label("否", ()) == "其他"
    # 空响应 → 其他
    assert _parse_label("", labels) == "其他"


def test_build_judge_prompt_renders_extract_key() -> None:
    """固定模板渲染 {extract_key}/{pred}/{labels} 三占位符；不暴露 {gold}。"""
    g = _lj_group()
    prompt = _build_judge_prompt(g, "不是", g.labels)
    assert "是否属实" in prompt  # extract_key 提取内容渲染进 prompt
    assert "否" in prompt and "是" in prompt  # labels 渲染（", ".join）
    assert "不是" in prompt  # pred 渲染
    assert "gold" not in prompt and "真值" not in prompt  # 不暴露 gold（防泄漏）
    # 不同 extract_key/labels 同样渲染
    g2 = GroupConfig(
        name="g",
        kind="llm_judge",
        pred_field="p",
        gold_field="g",
        labels=("a", "b"),
        extract_key="维度X",
    )
    p2 = _build_judge_prompt(g2, "x", g2.labels)
    assert "维度X" in p2 and "a, b" in p2 and "x" in p2


async def test_judge_phase_injects_labels_and_progress() -> None:
    """_judge 并发调 LLM，judged_label 经 side-channel 注入，推 judge 阶段进度。"""
    records = [
        {"id": "1", "gold": "否", "pred": "否"},
        {"id": "2", "gold": "否", "pred": "不是"},
        {"id": "3", "gold": "是", "pred": "否"},
        {"id": "4", "gold": "是", "pred": "是"},
    ]
    groups = [_lj_group()]
    materialized = await ingest(records, groups, "id")
    # LLM 返回标签文本：否/否/否/是（与 scorer 宏平均用例同构，correctness 1,1,0,1）
    model = _FakeJudgeModel(["否", "否", "否", "是"])
    job = job_manager.submit({"dataset_id": "ds-judge"})
    judged_labels = await _judge(job, materialized, groups, model)
    assert model.invoked == 4
    # 索引 0..3（每组 4 条全为 llm_judge）
    assert judged_labels == {0: "否", 1: "否", 2: "否", 3: "是"}
    phases = [e.data.get("phase") for e in job.get_events_since(0) if e.event == "progress"]
    assert "judge" in phases


async def test_judge_phase_llm_failure_degrades_to_other() -> None:
    """单条 LLM 异常 → judged_label "其他"，不抛、不阻断整批。"""

    class _BoomModel:
        async def invoke(self, messages: object) -> None:  # noqa: ARG002
            raise RuntimeError("LLM down")

    records = [{"id": "1", "gold": "否", "pred": "否"}]
    groups = [_lj_group()]
    materialized = await ingest(records, groups, "id")
    job = job_manager.submit({"dataset_id": "ds-boom"})
    judged_labels = await _judge(job, materialized, groups, _BoomModel())
    assert judged_labels == {0: "其他"}


async def test_judge_phase_no_judge_groups_returns_empty() -> None:
    """无 llm_judge 组（或 model=None）→ 空字典，不调 LLM。"""
    records = [{"id": "1", "gold": "否", "pred": "否"}]
    materialized = await ingest(records, [_em_group()], "id")
    job = job_manager.submit({"dataset_id": "ds-none"})
    model = _FakeJudgeModel(["否"])
    judged_labels = await _judge(job, materialized, [_em_group()], model)
    assert judged_labels == {}
    assert model.invoked == 0


async def test_run_offline_eval_llm_judge_group() -> None:
    """端到端：llm_judge 组 + 假 Model，四阶段进度，aggregate 全套非退化。"""
    records = [
        {"id": "1", "gold": "否", "pred": "否"},
        {"id": "2", "gold": "否", "pred": "不是"},
        {"id": "3", "gold": "是", "pred": "否"},
        {"id": "4", "gold": "是", "pred": "是"},
    ]
    model = _FakeJudgeModel(["否", "否", "否", "是"])
    job = job_manager.submit({"dataset_id": "ds-lj"})
    request = OfflineEvalRequest(
        dataset_id="ds-lj",
        raw_records=records,
        groups=[_lj_group()],
        id_field="id",
        model=model,
    )
    await run_offline_eval(job, request)
    assert job.status == JobStatus.COMPLETED
    assert job.result is not None
    assert job.result["extraction_summary"] == {"raw": 4}
    agg = job.result["aggregate"]["是否属实"]
    assert agg["llm_judge"] == 0.75  # mean correctness
    assert agg["_overall"] == 0.75
    assert agg["accuracy"] == 0.75
    # 否: tp2 fp1 fn0 → P=2/3 R=1.0；是: tp1 fp0 fn1 → P=1.0 R=0.5
    assert agg["precision"] == pytest.approx((2 / 3 + 1.0) / 2)
    assert agg["recall"] == pytest.approx((1.0 + 0.5) / 2)
    assert agg["f1"] == pytest.approx((0.8 + 2 / 3) / 2)
    # 诊断键
    assert agg["其他_count"] == 0.0
    # 四阶段进度
    phases = [e.data.get("phase") for e in job.get_events_since(0) if e.event == "progress"]
    assert "ingest" in phases and "judge" in phases
    assert "scoring" in phases and "aggregate" in phases


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
