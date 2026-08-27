from __future__ import annotations

from concurrent.futures import as_completed
from dataclasses import dataclass, field
from threading import Lock
from typing import Any, Callable, Dict, List, Optional

from backend.knowledge_qc.checkers.corpus_scope import format_row_ref
from backend.knowledge_qc.checkers.hit_filters import filter_hits_for_record
from backend.knowledge_qc.loaders.excel_loader import (
    DIMENSION_STATUS_FAIL,
    DIMENSION_STATUS_PASS,
    init_intent_dimension_statuses,
    is_checker_enabled,
)
from backend.knowledge_qc.config import load_settings
from backend.knowledge_qc.models import IntentBatchReport, IntentCheckResult, IntentRecord, Verdict
from backend.knowledge_qc.pipeline.cancel import is_cancelled
from backend.knowledge_qc.pipeline.workers import create_qc_thread_pool, qc_worker_count
from backend.knowledge_qc.report.checkpoint import build_intent_batch_report, maybe_emit_checkpoint
from backend.knowledge_qc.services.chroma_batch import create_chroma_store
from backend.knowledge_qc.services.embedder import Embedder, create_embedder
from backend.knowledge_qc.services.embedding_cache import preload_embedding_cache
from backend.knowledge_qc.services.llm import LLMClient, create_llm
from backend.knowledge_qc.services.vector_store import IntentBothQueryOpts


@dataclass
class IntentRunOpts:
    on_progress: Optional[Callable[[int, int, str], None]] = None
    on_log: Optional[Callable[[str], None]] = None
    checkpoint_interval: int = 0
    on_checkpoint: Optional[Callable[[IntentBatchReport], None]] = None
    should_cancel: Optional[Callable[[], bool]] = None
    row_indices: Optional[set] = None
    intent_filter: Optional[Dict[str, Any]] = None
    retry_mode: bool = False


@dataclass
class IntentCheckOpts:
    embedding_cache: Dict[str, List[float]] = field(default_factory=dict)
    top_k: int = 3
    threshold: float = 0.9
    batch_on: bool = True
    prod_on: bool = True
    intent_filter: Optional[Dict[str, Any]] = None


class IntentQualityPipeline:
    """意图描述质检：向量召回 TopK + LLM 裁决重复/包含/交叉。"""

    def __init__(self, settings: dict = None, embedder: Embedder = None):
        self.settings = settings or load_settings()
        self.rules = self.settings["rules"]
        self._vector_store = create_chroma_store(self.settings)
        self._embedder = embedder or create_embedder(self.settings)
        self._cache_lock = Lock()
        self._llm: Optional[LLMClient] = None
        if self.rules.get("checkers", {}).get("intent_llm", False):
            intent_cfg = self.rules.get("intent_qc", {})
            max_tokens = int(intent_cfg.get("llm_max_tokens", 512))
            self._llm = create_llm(
                {**self.settings, "llm_max_tokens": max_tokens}
            )

    def _intent_llm_enabled(self) -> bool:
        return self._llm is not None

    def run_records(
        self,
        records: List[IntentRecord],
        opts: Optional[IntentRunOpts] = None,
    ) -> IntentBatchReport:
        run_opts = opts or IntentRunOpts()
        on_progress = run_opts.on_progress
        on_log = run_opts.on_log
        checkpoint_interval = run_opts.checkpoint_interval
        on_checkpoint = run_opts.on_checkpoint
        should_cancel = run_opts.should_cancel
        row_indices = run_opts.row_indices
        intent_filter = run_opts.intent_filter
        retry_mode = run_opts.retry_mode
        total = len(records)
        intent_cfg = self.rules.get("intent_qc", {})
        top_k = int(intent_cfg.get("recall_top_k", 3))
        threshold = float(
            intent_cfg.get(
                "duplicate_threshold",
                self.rules.get("similarity", {}).get("duplicate_threshold", 0.9),
            )
        )

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        if row_indices:
            records = [r for r in records if r.row_index in row_indices]
            row_list = ", ".join(str(x) for x in sorted(row_indices))
            log(f"续检模式：按行号 {row_list} 过滤后待检 {len(records)} 条")

        mode = self.rules.get("detection_mode", {})
        batch_on = mode.get("batch", True)
        prod_on = mode.get("production", True)
        scope_parts = []
        if batch_on:
            scope_parts.append("本批 intent staging")
        if prod_on:
            scope_parts.append("意图生产库")
        log(
            f"共 {len(records)} 条意图描述待检，检索范围: "
            f"{' + '.join(scope_parts) if scope_parts else '无'}"
        )
        if intent_filter and intent_filter.get("mode") and intent_filter.get("intents"):
            log(f"生产库意图筛选：{intent_filter['mode']} {intent_filter['intents']}")
        if retry_mode:
            log("续检模式：复用已有意图向量库，跳过全表 Embedding 与 staging 重建")
        else:
            log("清空本批意图 staging…")
            if is_cancelled(should_cancel):
                log("用户已停止质检")
                return IntentBatchReport(total=0, passed=0, failed=0, errors=0, results=[])
            self._vector_store.clear_intent_staging()

        if is_cancelled(should_cancel):
            log("用户已停止质检")
            return IntentBatchReport(total=0, passed=0, failed=0, errors=0, results=[])
        if retry_mode:
            embedding_cache: Dict[str, List[float]] = {}
        else:
            log("批量 Embedding（整表去重）…")
            embedding_cache = preload_embedding_cache(
                self._embedder, [r.intent_description for r in records]
            )
            log(
                f"  完成：{len(records)} 条语料，{len(embedding_cache)} 个 distinct 向量"
            )

            if batch_on and records:
                log(f"批量写入本批 intent staging（共 {len(records)} 条）…")
                self._batch_index_intent_staging(records, embedding_cache)

        worker_count = qc_worker_count(self.settings)
        run_kw = dict(
            embedding_cache=embedding_cache,
            top_k=top_k,
            threshold=threshold,
            batch_on=batch_on,
            prod_on=prod_on,
            on_progress=on_progress,
            on_log=on_log,
            checkpoint_interval=checkpoint_interval,
            on_checkpoint=on_checkpoint,
            should_cancel=should_cancel,
            intent_filter=intent_filter,
        )
        if worker_count > 1:
            log(f"并发逐行质检（worker={worker_count}）…")
            results = self._run_checks_parallel(records, worker_count, **run_kw)
        else:
            results = self._run_checks_serial(records, **run_kw)

        if is_cancelled(should_cancel):
            log(f"已停止，完成 {len(results)}/{total} 条")
        passed = sum(1 for r in results if r.verdict == Verdict.PASS)
        failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
        errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
        log(f"检测结束：通过 {passed}，不通过 {failed}，异常 {errors}")

        return IntentBatchReport(
            total=len(results),
            passed=passed,
            failed=failed,
            errors=errors,
            results=results,
        )

    def _run_checks_serial(
        self,
        records: List[IntentRecord],
        *,
        embedding_cache: Dict[str, List[float]],
        top_k: int,
        threshold: float,
        batch_on: bool,
        prod_on: bool,
        on_progress: Callable[[int, int, str], None] | None,
        on_log: Callable[[str], None] | None,
        checkpoint_interval: int,
        on_checkpoint: Callable[[IntentBatchReport], None] | None,
        should_cancel: Callable[[], bool] | None = None,
        intent_filter: Optional[Dict[str, Any]] = None,
    ) -> List[IntentCheckResult]:
        total = len(records)
        results: List[IntentCheckResult] = []
        log_stride = max(1, total // 50) if total > 100 else 1

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        for i, record in enumerate(records):
            if is_cancelled(should_cancel):
                break
            if on_progress:
                on_progress(i + 1, total, record.intent_name)
            result = self._check_one(
                record,
                IntentCheckOpts(
                    embedding_cache=embedding_cache,
                    top_k=top_k,
                    threshold=threshold,
                    batch_on=batch_on,
                    prod_on=prod_on,
                    intent_filter=intent_filter,
                ),
            )
            results.append(result)
            if self._should_log_row(result, i, total, log_stride):
                self._log_row_result(i + 1, total, record, result, log)
            maybe_emit_checkpoint(
                results,
                i + 1,
                checkpoint_interval,
                on_checkpoint,
                build_intent_batch_report,
            )
            if on_checkpoint and checkpoint_interval > 0 and (i + 1) % checkpoint_interval == 0:
                log(f"  已增量写入 {i + 1}/{total} 条质检结果")
        return results

    def _run_checks_parallel(
        self,
        records: List[IntentRecord],
        worker_count: int,
        *,
        embedding_cache: Dict[str, List[float]],
        top_k: int,
        threshold: float,
        batch_on: bool,
        prod_on: bool,
        on_progress: Callable[[int, int, str], None] | None,
        on_log: Callable[[str], None] | None,
        checkpoint_interval: int,
        on_checkpoint: Callable[[IntentBatchReport], None] | None,
        should_cancel: Callable[[], bool] | None = None,
        intent_filter: Optional[Dict[str, Any]] = None,
    ) -> List[IntentCheckResult]:
        total = len(records)
        results: List[Optional[IntentCheckResult]] = [None] * total
        done = 0
        lock = Lock()
        log_stride = max(1, total // 50) if total > 100 else 1

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        def on_one_finished(
            index: int, record: IntentRecord, result: IntentCheckResult
        ) -> None:
            nonlocal done
            with lock:
                results[index] = result
                done += 1
                current = done
                snapshot = [r for r in results if r is not None]
            if on_progress:
                on_progress(current, total, record.intent_name)
            if self._should_log_row(result, index, total, log_stride):
                self._log_row_result(current, total, record, result, log)
            maybe_emit_checkpoint(
                snapshot,
                current,
                checkpoint_interval,
                on_checkpoint,
                build_intent_batch_report,
            )
            if on_checkpoint and checkpoint_interval > 0 and current % checkpoint_interval == 0:
                log(f"  已增量写入 {current}/{total} 条质检结果")

        check_opts = IntentCheckOpts(
            embedding_cache=embedding_cache,
            top_k=top_k,
            threshold=threshold,
            batch_on=batch_on,
            prod_on=prod_on,
            intent_filter=intent_filter,
        )
        with create_qc_thread_pool(worker_count) as pool:
            futures = {
                pool.submit(self._check_one, record, check_opts): i
                for i, record in enumerate(records)
            }
            for future in as_completed(futures):
                if is_cancelled(should_cancel):
                    for f in futures:
                        f.cancel()
                    break
                index = futures[future]
                record = records[index]
                try:
                    result = future.result()
                except Exception as e:
                    dim_statuses = init_intent_dimension_statuses(self.rules)
                    result = self._intent_result(
                        record,
                        Verdict.ERROR,
                        _format_intent_error(e),
                        dim_statuses,
                    )
                on_one_finished(index, record, result)

        return [r for r in results if r is not None]  # type: ignore[misc]

    @staticmethod
    def _should_log_row(
        result: IntentCheckResult, index: int, total: int, log_stride: int
    ) -> bool:
        return bool(
            result.verdict != Verdict.PASS
            or result.reason
            or index == 0
            or index + 1 == total
            or (index + 1) % log_stride == 0
        )

    @staticmethod
    def _log_row_result(
        current: int,
        total: int,
        record: IntentRecord,
        result: IntentCheckResult,
        log: Callable[[str], None],
    ) -> None:
        row = format_row_ref(record.row_index)
        log(f"[{current}/{total}] {row} {result.verdict.value} | {record.intent_name}")
        if result.reason:
            log(f"    └ {result.reason}")

    @staticmethod
    def _intent_result(
        record: IntentRecord,
        verdict: Verdict,
        reason: str,
        dim_statuses: Dict[str, str],
    ) -> IntentCheckResult:
        return IntentCheckResult(
            record=record,
            verdict=verdict,
            reason=reason,
            dimension_statuses=dim_statuses,
        )

    def _check_one(
        self,
        record: IntentRecord,
        opts: IntentCheckOpts,
    ) -> IntentCheckResult:
        embedding_cache = opts.embedding_cache
        top_k = opts.top_k
        threshold = opts.threshold
        batch_on = opts.batch_on
        prod_on = opts.prod_on
        intent_filter = opts.intent_filter
        dim_statuses = init_intent_dimension_statuses(self.rules)
        desc = record.intent_description.strip()
        if not desc:
            return self._intent_result(
                record, Verdict.FAIL, "意图描述为空", dim_statuses
            )
        if not record.intent_name.strip():
            return self._intent_result(
                record, Verdict.FAIL, "意图名称为空", dim_statuses
            )

        if not is_checker_enabled(self.rules, "重复检测"):
            return self._intent_result(record, Verdict.PASS, "", dim_statuses)

        embedding = self._get_embedding(desc, embedding_cache)
        hits = self._vector_store.query_intent_both(
            embedding,
            top_k,
            IntentBothQueryOpts(
                exclude_id=record.record_id,
                search_production=prod_on,
                search_staging=batch_on,
                intent_filter=intent_filter,
            ),
        )
        candidates = filter_hits_for_record(
            [h for h in hits if h["similarity"] >= threshold],
            record,
        )

        if not candidates:
            dim_statuses["重复检测"] = DIMENSION_STATUS_PASS
            return self._intent_result(record, Verdict.PASS, "", dim_statuses)

        if not self._intent_llm_enabled():
            dim_statuses["重复检测"] = DIMENSION_STATUS_FAIL
            top = candidates[0]
            reason = (
                f"意图描述与命中语料相似度 {float(top.get('similarity', 0)):.2f}，"
                f"可能存在重复或业务范围交叉（「{top.get('intent_name') or ''}」）"
            )
            return self._intent_result(record, Verdict.FAIL, reason, dim_statuses)

        try:
            llm_result = self._llm.judge_intent_description_duplicate(
                record.intent_name, desc, candidates, record.row_index
            )
        except Exception as e:
            dim_statuses["重复检测"] = DIMENSION_STATUS_FAIL
            return self._intent_result(
                record,
                Verdict.ERROR,
                _format_intent_error(e),
                dim_statuses,
            )
        if llm_result.get("pass", True):
            dim_statuses["重复检测"] = DIMENSION_STATUS_PASS
            return self._intent_result(record, Verdict.PASS, "", dim_statuses)

        dim_statuses["重复检测"] = DIMENSION_STATUS_FAIL
        return self._intent_result(
            record,
            Verdict.FAIL,
            llm_result.get("reason")
            or "意图描述与已有语料存在高度重复、包含或业务范围交叉",
            dim_statuses,
        )

    def _get_embedding(self, text: str, cache: Dict[str, List[float]]) -> List[float]:
        key = (text or "").strip()
        if not key:
            return []
        if key not in cache:
            with self._cache_lock:
                if key not in cache:
                    cache[key] = self._embedder.embed_one(key)
        return cache[key]

    def _batch_index_intent_staging(
        self, records: List[IntentRecord], cache: Dict[str, List[float]]
    ) -> None:
        embeddings = [
            self._get_embedding(r.intent_description, cache) for r in records
        ]
        self._vector_store.upsert_intent_records(
            self._vector_store.intent_staging, records, embeddings, "batch"
        )


def _format_intent_error(exc: BaseException) -> str:
    msg = str(exc).strip() or exc.__class__.__name__
    if "<html" in msg.lower():
        import re

        title = re.search(r"<title>\s*(.*?)\s*</title>", msg, flags=re.I | re.S)
        h1 = re.search(r"<h1>\s*(.*?)\s*</h1>", msg, flags=re.I | re.S)
        if title:
            msg = title.group(1).strip()
        elif h1:
            msg = h1.group(1).strip()
    if len(msg) > 300:
        msg = msg[:300] + "..."
    return f"质检异常：{msg}"
