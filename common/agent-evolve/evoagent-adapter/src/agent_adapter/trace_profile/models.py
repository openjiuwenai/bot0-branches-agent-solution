"""TraceProfile / SpanFilter / FieldExtraction 配置模型。

每个 Agent 一个 TraceProfile，定义 span 过滤规则和字段提取规则。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel


class SpanFilter(BaseModel):
    """span 过滤规则。

    - 黑名单优先：exclude 先于 include 判断。
    - 有白名单且未命中 → 拒绝；无白名单 → 默认放行。
    """
    include_prefixes: list[str] = []
    exclude_prefixes: list[str] = []
    include_names: list[str] = []
    exclude_names: list[str] = []


class FieldExtraction(BaseModel):
    """单个字段提取规则。

    attr:     span attributes 中的 key
    sub_key:  JSON 子键（如 "messages"、"outputs"）；空字符串表示取 attr 的值本身
    format:   "json" → json.loads；"python_repr" → repr_extract.extract_value
    """
    attr: str
    sub_key: str = ""
    format: Literal["json", "python_repr"] = "json"


class MultiFieldExtraction(BaseModel):
    """多字段拼接提取（如 OpenCode response = text + toolCalls + reasoning）。

    combine_as: "assistant_message" → 拼成 {"role":"assistant","content":text,...}
    """
    fields: list[FieldExtraction]
    combine_as: Literal["assistant_message", "messages_list"] = "assistant_message"


class TraceProfile(BaseModel):
    """一个 Agent 的完整轨迹处理规则。

    ingest_filter / query_filter: 两层过滤（ingest 粗过滤 → PG 入库；query 细过滤 → 转 record）
    llm_span_prefix / tool_span_prefix: LLM/Tool span 匹配前缀
    root_span_name: 根 span 名（空则不提取 TRACE record）
    """
    name: str
    service_name: str
    ingest_filter: SpanFilter = SpanFilter()
    query_filter: SpanFilter = SpanFilter()
    llm_span_prefix: str = ""
    tool_span_prefix: str = ""
    root_span_name: str = ""
    prompt_extraction: FieldExtraction = FieldExtraction(attr="")
    response_extraction: MultiFieldExtraction = MultiFieldExtraction(fields=[])
    tool_args_extraction: FieldExtraction = FieldExtraction(attr="")
    tool_result_extraction: FieldExtraction = FieldExtraction(attr="")
    request_summary_attr: str = "openjiuwen.http.request_body"
    response_summary_span_prefix: str = "chain."
    response_summary_attr: str = "openjiuwen.agent.outputs"