"""离线数据集评估路由（多组版）— multipart 上传 → 异步 job → 进度查询 + SSE。

与 Trainer 评估流无关、不进 dataset.yaml。流程：上传数据集（json/jsonl/csv/xlsx）
→ 多组配置（每组 kind + gold 源 + pred 列 + batch_metrics；含 llm_judge 组时顶层
附 llm_config）→ 逐条评分（llm_judge 组先并发调 LLM 把 pred 分类到声明标签）→ 按组聚合
→ 返回逐条 + 按组嵌套总体。kind ∈ {exact_match, keyword, llm_judge}。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections.abc import AsyncGenerator
from typing import Any, Literal

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from evo_agent.api.jobs import JobStatus, job_manager
from evo_agent.api.sse import format_sse
from evo_agent.evaluator.offline import (
    VALID_BATCH_METRICS,
    GroupConfig,
    OfflineEvalRequest,
    load_raw_records,
    run_offline_eval,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/evaluate/dataset", tags=["evaluate-dataset"])

# 上传体量上限——整段读入内存解析，故较保守。100 MB。
_MAX_UPLOAD_BYTES = 100 * 1024 * 1024

# 合法组 kind。
_KINDS = ("exact_match", "keyword", "llm_judge")


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------


class LLMConfig(BaseModel):
    """LLM 配置——含 ``llm_judge`` 组时顶层必传。

    与 ``evaluate.py`` 的 ``LLMConfig`` 同构；此处本地定义以避免跨 route import
    拉入 evaluator domain 依赖。``client_provider`` 默认 ``"OpenAI"``；用自定义 SSE
    端点时传 ``"CustomSSE"``（``import evo_agent.llm`` 已在提交路由触发注册）。
    """

    model_name: str = ""
    api_key: str
    api_base: str
    client_provider: str = "OpenAI"
    temperature: float = 0.0
    max_tokens: int = 64
    verify_ssl: bool = False
    # 透传到 OpenAI ``chat.completions.create`` 的 ``extra_body``（SDK 丢未知顶层键）。
    # 用途：DashScope qwen3 关思考 ``{"enable_thinking": false}`` 等厂商专属参数。
    extra_body: dict[str, Any] | None = None


class GroupConfigRequest(BaseModel):
    """一个评估组的配置。"""

    name: str
    kind: Literal["exact_match", "keyword", "llm_judge"]
    pred_field: str
    gold_field: str = ""
    keywords: list[str] = Field(default_factory=list)
    json_key: str = ""
    labels: list[str] = Field(default_factory=list)
    extract_key: str = ""
    batch_metrics: list[str] = Field(
        default=["mean", "precision", "recall", "f1", "accuracy"], min_length=1
    )


class DatasetEvalConfig(BaseModel):
    """评估配置——id_field + groups（至少 1 组）。``llm_config`` 仅含 llm_judge 组时必填。"""

    id_field: str = ""
    groups: list[GroupConfigRequest] = Field(min_length=1)
    llm_config: LLMConfig | None = None


class DatasetEvalSubmitResponse(BaseModel):
    """提交响应。"""

    job_id: str
    dataset_id: str
    status: str


class JobResponse(BaseModel):
    """任务状态响应。"""

    job_id: str
    status: str
    progress: dict[str, Any] | None = None
    result: dict[str, Any] | None = None
    error: str | None = None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _validate_group(g: GroupConfigRequest) -> None:
    """校验单组配置：kind 合法、exact_match/llm_judge 有 gold_field、keyword 有
    keywords、llm_judge 有非空 ``labels`` 且不含保留词 ``"其他"`` 且有 ``extract_key``、
    batch_metrics 名合法（``VALID_BATCH_METRICS`` 之一）。不合法 → ValueError。"""
    if g.kind not in _KINDS:
        raise ValueError(f"Group {g.name!r}: unknown kind {g.kind!r}")
    if g.kind in ("exact_match", "llm_judge") and not g.gold_field:
        raise ValueError(f"Group {g.name!r} ({g.kind}) requires gold_field")
    if g.kind == "keyword" and not g.keywords:
        raise ValueError(f"Group {g.name!r} (keyword) requires non-empty keywords")
    if g.kind == "llm_judge":
        if not g.labels:
            raise ValueError(f"Group {g.name!r} (llm_judge) requires non-empty labels")
        if "其他" in g.labels:
            raise ValueError(
                f"Group {g.name!r} (llm_judge): '其他' is a reserved label, cannot be declared"
            )
        if not g.extract_key:
            raise ValueError(f"Group {g.name!r} (llm_judge) requires extract_key")
    valid = set(VALID_BATCH_METRICS)
    for name in dict.fromkeys(g.batch_metrics):
        if name not in valid:
            raise ValueError(
                f"Group {g.name!r}: unknown batch metric {name!r}; valid: {sorted(valid)}"
            )


def _to_group_config(g: GroupConfigRequest) -> GroupConfig:
    """请求模型 → 内部 GroupConfig。"""
    return GroupConfig(
        name=g.name,
        kind=g.kind,
        pred_field=g.pred_field,
        gold_field=g.gold_field,
        keywords=tuple(g.keywords),
        json_key=g.json_key,
        labels=tuple(g.labels),
        extract_key=g.extract_key,
        batch_metrics=tuple(dict.fromkeys(g.batch_metrics)),
    )


def _build_judge_model(llm_config: LLMConfig) -> Any:
    """从 ``LLMConfig`` 构建 ``Model`` 实例（供 llm_judge 阶段调用）。

    ``import evo_agent.llm`` 触发 CustomSSE provider 注册；``Model(client_config,
    model_config)`` 见 ``optimizer_runner._create_llm``。

    ``client_provider`` 为自由字符串，openjiuwen 在 ``ModelClientConfig`` 构造时
    校验 provider 是否已注册；未知 provider 抛
    :class:`openjiuwen.core.common.exception.errors.ValidationError`（非 pydantic
    ``ValidationError``），此处捕获并转为 422，避免冒泡为 500。
    """
    from openjiuwen.core.common.exception.errors import (
        ValidationError as ProviderValidationError,
    )
    from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig

    import evo_agent.llm  # noqa: F401 — 注册 CustomSSE provider（幂等）

    model_config = ModelRequestConfig(
        model_name=llm_config.model_name,
        temperature=llm_config.temperature,
        max_tokens=llm_config.max_tokens,
    )
    try:
        client_config = ModelClientConfig(
            client_provider=llm_config.client_provider,
            api_key=llm_config.api_key,
            api_base=llm_config.api_base,
            verify_ssl=llm_config.verify_ssl,
        )
    except ProviderValidationError as e:
        raise HTTPException(
            status_code=422,
            detail=f"Invalid llm_config: {e}",
        ) from e
    if llm_config.extra_body:
        # extra="allow" → model_dump 后进 chat.completions.create(extra_body=...)
        model_config.extra_body = llm_config.extra_body
    return Model(client_config, model_config)


async def _probe_judge_model(model: Any) -> None:
    """提交期用最小 prompt 探测一次 LLM 调用——无效 api_key 等调用级失败 → 500。

    **不触碰 judge 阶段单条降级「其他」的内在逻辑**（批内瞬时失败仍降级、不阻断整批）；
    此探测仅把「配置级」LLM 调用失败（无效 api_key、连接失败、模型调用失败等）在提交期
    同步暴露为 500，与 ``/evaluate`` 的「LLM 失败 → 500」契约（``docs/api/evaluate-api.md``
    §状态码）一致，消除两路由在「无效 api_key」上的行为不一致（DEFECT-003）。

    provider 无关——不区分 auth / 网络错误，任意调用异常统一 500（同 ``judge`` 降级哲学：
    「不区分特定端点的凭证错误，保持与 provider 解耦」）。
    """
    from openjiuwen.core.foundation.llm import UserMessage

    try:
        await model.invoke([UserMessage(content="ping")])
    except Exception as e:  # noqa: BLE001 — 任意调用级失败统一 500，与 /evaluate 一致
        raise HTTPException(
            status_code=500,
            detail=f"LLM judge config probe failed: {e}",
        ) from e


def _progress_from_job(job: Any) -> dict[str, Any] | None:
    """从 job 事件 buffer 取最新 progress 事件，派生 {phase, done, total}。"""
    events = job.get_events_since(0)
    for event in reversed(events):
        if event.event == "progress":
            data = event.data
            return {
                "phase": data.get("phase"),
                "done": data.get("done"),
                "total": data.get("total"),
            }
    return None


def _new_dataset_id() -> str:
    return uuid.uuid4().hex[:16]


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@router.post("", response_model=DatasetEvalSubmitResponse)
async def submit_dataset_eval(
    file: UploadFile = File(...),
    config: str = Form(...),
) -> DatasetEvalSubmitResponse:
    """multipart 上传数据集并提交评估 job。

    - ``file``：数据集文件（json/jsonl/csv/xlsx）。
    - ``config``：JSON blob，解析为 :class:`DatasetEvalConfig`。
    """
    try:
        parsed = DatasetEvalConfig.model_validate_json(config)
    except (ValueError, json.JSONDecodeError) as e:
        raise HTTPException(status_code=422, detail=f"Invalid config: {e}") from e

    # 校验每组配置（batch 名、kind、gold/keywords 完备性）。
    try:
        for g in parsed.groups:
            _validate_group(g)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    # 含 llm_judge 组时顶层 llm_config 必填，并据此构建 Model。
    has_judge = any(g.kind == "llm_judge" for g in parsed.groups)
    if has_judge and parsed.llm_config is None:
        raise HTTPException(
            status_code=422,
            detail="LLM judge group requires top-level llm_config",
        )
    judge_model: Any = None
    if has_judge:
        assert parsed.llm_config is not None  # 上面已校验
        judge_model = _build_judge_model(parsed.llm_config)

    data = await file.read()
    if len(data) > _MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Upload too large: {len(data)} > {_MAX_UPLOAD_BYTES}",
        )

    try:
        raw_records = load_raw_records(data, file.filename)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e

    # llm_judge 组：gold 列的 distinct 值必须 ⊆ 声明的 labels（422，清数据）。
    for g in parsed.groups:
        if g.kind != "llm_judge":
            continue
        gold_values = {str(r.get(g.gold_field)) for r in raw_records}
        undeclared = sorted(gold_values - set(g.labels))
        if undeclared:
            raise HTTPException(
                status_code=422,
                detail=(
                    f"Group {g.name!r}: gold values {undeclared} not in declared "
                    f"labels {list(g.labels)}"
                ),
            )

    # 所有 422 数据校验通过后，提交期探测一次 LLM 调用——无效 api_key 等配置级失败
    # 同步返回 500（与 /evaluate 的「LLM 失败→500」契约一致）；不替代 judge 阶段
    # 单条降级逻辑。放最后以确保 422 数据/配置校验优先于 LLM 调用。
    if has_judge:
        assert judge_model is not None
        await _probe_judge_model(judge_model)

    groups = [_to_group_config(g) for g in parsed.groups]

    dataset_id = _new_dataset_id()
    job = job_manager.submit({"dataset_id": dataset_id})
    request = OfflineEvalRequest(
        dataset_id=dataset_id,
        raw_records=raw_records,
        groups=groups,
        id_field=parsed.id_field,
        model=judge_model,
    )

    async def _run() -> None:
        await run_offline_eval(job, request)

    job.background_task = asyncio.create_task(_run())
    return DatasetEvalSubmitResponse(
        job_id=job.job_id,
        dataset_id=dataset_id,
        status=job.status.value,
    )


@router.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(job_id: str) -> JobResponse:
    """查询评估任务状态、进度与结果。"""
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")
    return JobResponse(
        job_id=job.job_id,
        status=job.status.value,
        progress=_progress_from_job(job),
        result=job.result,
        error=job.error,
    )


@router.get("/jobs/{job_id}/stream")
async def stream_job(job_id: str, request: Request) -> StreamingResponse:
    """SSE 流式推送评估进度事件。支持 ``Last-Event-ID`` 重放历史。"""
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"Job not found: {job_id}")

    last_event_id = int(request.headers.get("Last-Event-ID", "0"))

    async def event_generator() -> AsyncGenerator[str, None]:
        replayed = job.get_events_since(last_event_id)
        for event in replayed:
            yield format_sse(event)
        current_last = replayed[-1].id if replayed else last_event_id

        if job.status in (JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED):
            return

        last_keepalive = time.monotonic()
        while job.status not in (
            JobStatus.COMPLETED,
            JobStatus.FAILED,
            JobStatus.CANCELLED,
        ):
            await asyncio.sleep(0.5)
            new_events = job.get_events_since(current_last)
            for event in new_events:
                yield format_sse(event)
                current_last = event.id
                last_keepalive = time.monotonic()
            if time.monotonic() - last_keepalive >= 30:
                yield ": keepalive\n\n"
                last_keepalive = time.monotonic()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
    )
