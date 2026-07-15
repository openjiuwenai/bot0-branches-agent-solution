"""evaluate_dataset 路由测试（多组版）—— multipart 提交、进度查询、4 种模式、校验。"""

from __future__ import annotations

import io
import json
import time
from typing import Any

import pytest
from fastapi.testclient import TestClient

from evo_agent.api.app import create_app
from evo_agent.api.jobs import job_manager


def _xlsx_bytes(rows: list[list[object]]) -> bytes:
    """构造单 sheet xlsx，首行为表头。"""
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    for r in rows:
        ws.append(r)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _cfg(groups: list[dict[str, Any]], *, id_field: str = "id") -> dict[str, Any]:
    return {"id_field": id_field, "groups": groups}


def _em_group(name: str, gold: str, pred: str) -> dict[str, Any]:
    return {
        "name": name,
        "kind": "exact_match",
        "pred_field": pred,
        "gold_field": gold,
        "batch_metrics": ["mean", "precision", "recall", "f1", "accuracy"],
    }


def _kw_group(name: str, pred: str, keywords: list[str]) -> dict[str, Any]:
    return {
        "name": name,
        "kind": "keyword",
        "pred_field": pred,
        "keywords": keywords,
        "batch_metrics": ["mean", "precision", "recall", "f1", "accuracy"],
    }


def _json_em_group(name: str, json_key: str, gold: str) -> dict[str, Any]:
    """pred 共存于同一 JSON 列 ``result``，按 json_key 取该组 pred。"""
    return {
        "name": name,
        "kind": "exact_match",
        "pred_field": "result",
        "json_key": json_key,
        "gold_field": gold,
        "batch_metrics": ["mean", "precision", "recall", "f1", "accuracy"],
    }


def _submit(
    client: TestClient, cfg: dict[str, Any], data: bytes, fname: str = "items.json"
) -> dict[str, Any]:
    resp = client.post(
        "/evaluate/dataset",
        files={"file": (fname, data, "application/octet-stream")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def _wait_terminal(client: TestClient, job_id: str, timeout: float = 10.0) -> dict[str, Any]:
    elapsed = 0.0
    while elapsed < timeout:
        resp = client.get(f"/evaluate/dataset/jobs/{job_id}")
        assert resp.status_code == 200
        body = resp.json()
        if body["status"] in ("completed", "failed", "cancelled"):
            return body
        time.sleep(0.02)
        elapsed += 0.02
    raise AssertionError(f"job {job_id} timed out")


@pytest.fixture(autouse=True)
def _reset_job_store() -> Any:
    """每个测试前清空 job_manager，避免相互污染。"""
    job_manager._jobs.clear()
    yield


# ---------------------------------------------------------------------------
# 模式 1：单组 exact_match
# ---------------------------------------------------------------------------


def test_mode1_single_exact_match_json() -> None:
    client = TestClient(create_app())
    data = b'[{"id":"1","gold":"Paris","pred":"Paris"},{"id":"2","gold":"London","pred":"Paris"}]'
    cfg = _cfg([_em_group("是否属实", "gold", "pred")])
    submit = _submit(client, cfg, data)
    assert submit["status"] == "queued"
    body = _wait_terminal(client, submit["job_id"])
    assert body["status"] == "completed"
    result = body["result"]
    assert result is not None
    agg = result["aggregate"]["是否属实"]
    assert agg["exact_match"] == 0.5
    assert "_overall" in agg and "f1" in agg and "accuracy" in agg
    assert result["extraction_summary"] == {"raw": 2}
    assert result["per_case"][0]["case_id"] == "1"


# ---------------------------------------------------------------------------
# 模式 2：多组 exact_match
# ---------------------------------------------------------------------------


def test_mode2_multi_exact_match() -> None:
    client = TestClient(create_app())
    records = [
        {"id": "1", "gold": "否", "pred": "否", "gold2": "否", "pred2": "否"},
        {"id": "2", "gold": "否", "pred": "是", "gold2": "否", "pred2": "否"},
    ]
    data = json.dumps(records, ensure_ascii=False).encode()
    cfg = _cfg([_em_group("是否属实", "gold", "pred"), _em_group("是否供电", "gold2", "pred2")])
    submit = _submit(client, cfg, data)
    body = _wait_terminal(client, submit["job_id"])
    result = body["result"]
    assert set(result["aggregate"].keys()) == {"是否属实", "是否供电"}
    assert result["aggregate"]["是否属实"]["exact_match"] == 0.5
    assert result["aggregate"]["是否供电"]["exact_match"] == 1.0


# ---------------------------------------------------------------------------
# 模式 3：关键词单组
# ---------------------------------------------------------------------------


def test_mode3_keyword_group() -> None:
    client = TestClient(create_app())
    records = [{"id": "1", "text": "该诉求属实"}, {"id": "2", "text": "无相关词"}]
    data = json.dumps(records, ensure_ascii=False).encode()
    cfg = _cfg([_kw_group("责任判定", "text", ["属实", "供电公司责任"])])
    submit = _submit(client, cfg, data)
    body = _wait_terminal(client, submit["job_id"])
    result = body["result"]
    agg = result["aggregate"]["责任判定"]
    assert agg["keyword_hit"] == 0.5  # 命中率
    assert agg["precision"] == 1.0
    assert agg["recall"] == 0.5
    assert agg["accuracy"] == 0.5
    assert agg["f1"] == pytest.approx(2 * 0.5 / 1.5)


# ---------------------------------------------------------------------------
# 模式 4：混合多组（exact_match + keyword）
# ---------------------------------------------------------------------------


def test_mode4_mixed_groups() -> None:
    client = TestClient(create_app())
    records = [
        {"id": "1", "gold": "否", "pred": "否", "text": "属实"},
        {"id": "2", "gold": "否", "pred": "是", "text": "无"},
    ]
    data = json.dumps(records, ensure_ascii=False).encode()
    cfg = _cfg([_em_group("是否属实", "gold", "pred"), _kw_group("责任判定", "text", ["属实"])])
    submit = _submit(client, cfg, data)
    body = _wait_terminal(client, submit["job_id"])
    result = body["result"]
    assert set(result["aggregate"].keys()) == {"是否属实", "责任判定"}
    # per_case 按 case 嵌套：每 case 的 groups 含两组
    g1 = result["per_case"][0]["groups"]
    assert "是否属实" in g1 and "责任判定" in g1
    # 跨组 overall：_overall = 各组 composite 均值宏平均 = (0.5+0.5)/2 = 0.5；
    # P/R/F1/Acc = 各组混淆 bundle 宏平均（overall 恒算全套，与勾选无关）：
    #   是否属实(em,否2 是0,1对1错): P=0.5 R=0.25 F1=0.3333 Acc=0.5
    #   责任判定(kw,1命中1未中):       P=1.0 R=0.5  F1=0.6667 Acc=0.5
    #   → overall: P=0.75 R=0.375 F1=0.5 Acc=0.5（混 keyword，precision 被 1.0 抬高）
    ov = result["overall"]
    assert ov["_overall"] == pytest.approx(0.5)
    assert ov["precision"] == pytest.approx(0.75)
    assert ov["recall"] == pytest.approx(0.375)
    assert ov["f1"] == pytest.approx(0.5)
    assert ov["accuracy"] == pytest.approx(0.5)


# ---------------------------------------------------------------------------
# xlsx 上传
# ---------------------------------------------------------------------------


def test_xlsx_upload_mode1() -> None:
    client = TestClient(create_app())
    data = _xlsx_bytes([["序号", "是否属实", "是否属实_pred"], [1, "否", "否"], [2, "否", "是"]])
    cfg = _cfg([_em_group("是否属实", "是否属实", "是否属实_pred")], id_field="序号")
    submit = _submit(client, cfg, data, fname="数据集1.xlsx")
    body = _wait_terminal(client, submit["job_id"])
    result = body["result"]
    agg = result["aggregate"]["是否属实"]
    assert agg["exact_match"] == 0.5


# ---------------------------------------------------------------------------
# 模式 5：JSON 列提取（两组 pred 共存于同一 JSON 列）
# ---------------------------------------------------------------------------


def test_mode5_json_column_extraction() -> None:
    """两组 pred 塞在同一 JSON 列 ``result``，各设不同 json_key 解析。"""
    client = TestClient(create_app())
    records = [
        {
            "id": "1",
            "是否属实": "否",
            "是否供电公司责任": "是",
            "result": '{"是否属实":"否","是否供电公司责任":"是"}',
        },
        {
            "id": "2",
            "是否属实": "是",
            "是否供电公司责任": "否",
            "result": '{"是否属实":"是","是否供电公司责任":"否"}',
        },
        {
            "id": "3",
            "是否属实": "是",
            "是否供电公司责任": "否",
            "result": '{"是否属实":"否","是否供电公司责任":"否"}',
        },  # 是否属实 误判
    ]
    data = json.dumps(records, ensure_ascii=False).encode()
    cfg = _cfg(
        [
            _json_em_group("是否属实", "是否属实", "是否属实"),
            _json_em_group("是否供电公司责任", "是否供电公司责任", "是否供电公司责任"),
        ]
    )
    submit = _submit(client, cfg, data)
    body = _wait_terminal(client, submit["job_id"])
    assert body["status"] == "completed"
    result = body["result"]
    assert set(result["aggregate"].keys()) == {"是否属实", "是否供电公司责任"}
    # 是否属实: 3 条中 2 条对（id3 误判）→ accuracy 2/3
    assert result["aggregate"]["是否属实"]["exact_match"] == pytest.approx(2 / 3)
    # 是否供电公司责任: 3 条全对 → 1.0
    assert result["aggregate"]["是否供电公司责任"]["exact_match"] == 1.0
    assert result["extraction_summary"] == {"json": 6}  # 3 条 × 2 组


def _lj_group(
    name: str, gold: str, pred: str, *, labels: list[str] | None = None,
    extract_key: str = "是否属实",
) -> dict[str, Any]:
    return {
        "name": name,
        "kind": "llm_judge",
        "pred_field": pred,
        "gold_field": gold,
        "labels": labels if labels is not None else ["否", "是"],
        "extract_key": extract_key,
        "batch_metrics": ["mean", "precision", "recall", "f1", "accuracy"],
    }


class _FakeAssistant:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeJudgeModel:
    """按预设序列依次返回 LLM 响应文本的假 Model（替代真实 _build_judge_model）。"""

    def __init__(self, contents: list[str]) -> None:
        self._contents = contents
        self._i = 0

    async def invoke(self, messages: object) -> _FakeAssistant:  # noqa: ARG002
        content = self._contents[self._i % len(self._contents)]
        self._i += 1
        return _FakeAssistant(content)


# ---------------------------------------------------------------------------
# 模式 6：llm_judge
# ---------------------------------------------------------------------------


def test_mode6_llm_judge(monkeypatch: pytest.MonkeyPatch) -> None:
    """llm_judge 组 + 假 Model（monkeypatch _build_judge_model）：四阶段进度、aggregate 非退化。"""
    import evo_agent.api.routes.evaluate_dataset as route_mod

    records = [
        {"id": "1", "gold": "否", "pred": "否"},
        {"id": "2", "gold": "否", "pred": "不是"},
        {"id": "3", "gold": "是", "pred": "否"},
        {"id": "4", "gold": "是", "pred": "是"},
    ]
    data = json.dumps(records, ensure_ascii=False).encode()
    cfg = {
        "id_field": "id",
        "groups": [_lj_group("是否属实", "gold", "pred")],
        "llm_config": {
            "api_key": "k",
            "api_base": "http://x",
            "client_provider": "OpenAI",
        },
    }
    monkeypatch.setattr(
        route_mod,
        "_build_judge_model",
        lambda _cfg: _FakeJudgeModel(["否", "否", "否", "是"]),
    )
    client = TestClient(create_app())
    submit = _submit(client, cfg, data)
    body = _wait_terminal(client, submit["job_id"])
    assert body["status"] == "completed", body
    result = body["result"]
    assert result is not None
    assert result["extraction_summary"] == {"raw": 4}
    agg = result["aggregate"]["是否属实"]
    assert agg["llm_judge"] == 0.75
    assert agg["accuracy"] == 0.75
    assert agg["precision"] == pytest.approx((2 / 3 + 1.0) / 2)  # 非退化
    assert agg["recall"] == pytest.approx((1.0 + 0.5) / 2)
    assert agg["f1"] == pytest.approx((0.8 + 2 / 3) / 2)
    ov = result["overall"]
    assert ov["_overall"] == pytest.approx(0.75)
    assert ov["accuracy"] == ov["_overall"]  # llm_judge-only 恒等


# ---------------------------------------------------------------------------
# 校验：422
# ---------------------------------------------------------------------------


def test_unknown_batch_metric_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg(
        [
            {
                "name": "g",
                "kind": "exact_match",
                "pred_field": "pred",
                "gold_field": "gold",
                "batch_metrics": ["not_a_metric"],
            }
        ]
    )
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","gold":"x","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "not_a_metric" in resp.text


def test_empty_groups_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg([])
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","gold":"x","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422


def test_keyword_empty_keywords_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg([_kw_group("g", "pred", [])])
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422


def test_exact_match_missing_gold_field_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg(
        [
            {
                "name": "g",
                "kind": "exact_match",
                "pred_field": "pred",
                "gold_field": "",
                "batch_metrics": ["mean"],
            }
        ]
    )
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422


def test_empty_batch_metrics_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg(
        [
            {
                "name": "g",
                "kind": "exact_match",
                "pred_field": "pred",
                "gold_field": "gold",
                "batch_metrics": [],
            }
        ]
    )
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","gold":"x","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422


def test_get_job_not_found_404() -> None:
    client = TestClient(create_app())
    resp = client.get("/evaluate/dataset/jobs/missing")
    assert resp.status_code == 404


def test_llm_judge_without_llm_config_422() -> None:
    client = TestClient(create_app())
    cfg = _cfg(
        [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "gold",
                "labels": ["否", "是"],
                "extract_key": "是否属实",
                "batch_metrics": ["mean"],
            }
        ]
    )  # 无顶层 llm_config
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","gold":"x","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "llm_config" in resp.text


def test_llm_judge_missing_gold_field_422() -> None:
    client = TestClient(create_app())
    cfg = {
        "id_field": "id",
        "groups": [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "",
                "labels": ["否", "是"],
                "batch_metrics": ["mean"],
            }
        ],
        "llm_config": {"api_key": "k", "api_base": "http://x"},
    }
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", b'{"id":"1","pred":"x"}', "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "gold_field" in resp.text


def test_llm_judge_missing_labels_422() -> None:
    """llm_judge 组未声明 labels → 422（gold_field 已给，先过 gold_field 再命中 labels）。"""
    client = TestClient(create_app())
    cfg = {
        "id_field": "id",
        "groups": [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "gold",
                "batch_metrics": ["mean"],
            }
        ],
        "llm_config": {"api_key": "k", "api_base": "http://x"},
    }
    data = json.dumps({"id": "1", "gold": "否", "pred": "否"}, ensure_ascii=False).encode()
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", data, "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "labels" in resp.text


def test_llm_judge_missing_extract_key_422() -> None:
    """llm_judge 缺 extract_key（提取内容）→ 422（labels 已给，过 labels 再命中）。"""
    client = TestClient(create_app())
    cfg = {
        "id_field": "id",
        "groups": [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "gold",
                "labels": ["否", "是"],
                "extract_key": "",
                "batch_metrics": ["mean"],
            }
        ],
        "llm_config": {"api_key": "k", "api_base": "http://x"},
    }
    data = json.dumps({"id": "1", "gold": "否", "pred": "否"}, ensure_ascii=False).encode()
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", data, "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "extract_key" in resp.text


def test_llm_judge_other_in_labels_422() -> None:
    """声明了保留词「其他」→ 422。"""
    client = TestClient(create_app())
    cfg = _cfg(
        [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "gold",
                "labels": ["否", "是", "其他"],
                "batch_metrics": ["mean"],
            }
        ],
    )
    data = json.dumps({"id": "1", "gold": "否", "pred": "否"}, ensure_ascii=False).encode()
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", data, "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "reserved" in resp.text


def test_llm_judge_gold_not_in_labels_422() -> None:
    """gold 列出现未声明值 → 422（清数据，符合「只有声明」契约）。"""
    client = TestClient(create_app())
    cfg = {
        "id_field": "id",
        "groups": [
            {
                "name": "g",
                "kind": "llm_judge",
                "pred_field": "pred",
                "gold_field": "gold",
                "labels": ["否", "是"],
                "extract_key": "是否属实",
                "batch_metrics": ["mean"],
            }
        ],
        "llm_config": {"api_key": "k", "api_base": "http://x"},
    }
    # gold 出现 "未知" 不在 labels 里
    data = json.dumps({"id": "1", "gold": "未知", "pred": "否"}, ensure_ascii=False).encode()
    resp = client.post(
        "/evaluate/dataset",
        files={"file": ("items.json", data, "application/json")},
        data={"config": json.dumps(cfg)},
    )
    assert resp.status_code == 422
    assert "not in declared labels" in resp.text


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
