"""共享测试数据定位: 真实 EDPAgent 上报的 OTel span 样本。

`otel_spans.jsonl` 归档于 `evoagent-adapter/tests/data/` 下, 供 unit/integration
测试共用, 不再依赖仓库根的 `mock-assets/...` 路径布局 (避免跨机器/跨仓库布局差异
导致用例无法定位数据文件)。

数据来源: EDPAgent 真实 OTLP 上报经 otlp_relay.py 捕获后转成 parse_span 扁平形状
(无 duration_ns, session_id 提升自 attributes."session.id")。
"""

from __future__ import annotations

from pathlib import Path

# tests/_testdata.py -> tests/data/otel_spans.jsonl
OTEL_SPANS_JSONL = Path(__file__).resolve().parent / "data" / "otel_spans.jsonl"


def otel_spans_jsonl() -> Path:
    """返回归档在 tests/data 下的 jsonl 路径, 缺失时断言失败。"""
    assert OTEL_SPANS_JSONL.exists(), f"测试数据缺失: {OTEL_SPANS_JSONL}"
    return OTEL_SPANS_JSONL
