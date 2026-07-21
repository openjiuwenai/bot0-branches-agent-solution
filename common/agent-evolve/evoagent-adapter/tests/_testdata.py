"""共享测试数据定位: 模拟 EDPAgent 上报的 OTel span 样本。

`otel_spans_v2.jsonl` 归档于 `evoagent-adapter/tests/data/` 下, 供 unit/integration
测试共用, 不再依赖仓库根的 `mock-assets/...` 路径布局 (避免跨机器/跨仓库布局差异
导致用例无法定位数据文件)。
"""

from __future__ import annotations

from pathlib import Path

# tests/_testdata.py -> tests/data/otel_spans_v2.jsonl
OTEL_SPANS_JSONL = Path(__file__).resolve().parent / "data" / "otel_spans_v2.jsonl"


def otel_spans_jsonl() -> Path:
    """返回归档在 tests/data 下的 jsonl 路径, 缺失时断言失败。"""
    assert OTEL_SPANS_JSONL.exists(), f"测试数据缺失: {OTEL_SPANS_JSONL}"
    return OTEL_SPANS_JSONL
