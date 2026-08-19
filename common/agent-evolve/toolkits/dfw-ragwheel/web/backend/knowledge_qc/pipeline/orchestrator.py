from __future__ import annotations

from concurrent.futures import as_completed
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Any, Callable, Dict, List, Optional

from backend.knowledge_qc.checkers.base import BaseChecker, CheckContext
from backend.knowledge_qc.checkers.compliance_checker import ComplianceChecker
from backend.knowledge_qc.checkers.conflict_checker import ConflictChecker
from backend.knowledge_qc.checkers.duplicate_checker import DuplicateChecker
from backend.knowledge_qc.checkers.format_checker import FormatChecker
from backend.knowledge_qc.checkers.corpus_scope import format_row_ref
from backend.knowledge_qc.checkers.semantic_checker import SemanticChecker
from backend.knowledge_qc.config import load_settings
from backend.knowledge_qc.loaders.excel_loader import (
    DIMENSION_STATUS_FAIL,
    DIMENSION_STATUS_PASS,
    mark_dimensions_skipped,
)
from backend.knowledge_qc.models import BatchReport, CheckResult, Issue, QARecord, Verdict
from backend.knowledge_qc.pipeline.row_range import (
    filter_by_row_range,
    format_row_range_log,
)
from backend.knowledge_qc.pipeline.cancel import is_cancelled
from backend.knowledge_qc.pipeline.workers import create_qc_thread_pool, qc_worker_count
from backend.knowledge_qc.report.checkpoint import build_batch_report, maybe_emit_checkpoint
from backend.knowledge_qc.services.chroma_batch import create_chroma_store
from backend.knowledge_qc.services.embedder import Embedder, create_embedder
from backend.knowledge_qc.services.embedding_cache import preload_embedding_cache
from backend.knowledge_qc.services.llm import LLMClient, create_llm
from backend.knowledge_qc.services.similarity_preload import (
    count_similarity_hits,
    preload_similarity_hits,
    restore_similarity_hits_cache,
    similarity_query_batch_size,
)


@dataclass
class QuestionRunOpts:
    seed_production: bool = False
    on_progress: Optional[Callable[[int, int, str], None]] = None
    on_log: Optional[Callable[[str], None]] = None
    checkpoint_interval: int = 0
    on_checkpoint: Optional[Callable[[BatchReport], None]] = None
    should_cancel: Optional[Callable[[], bool]] = None
    row_indices: Optional[set] = None
    retry_mode: bool = False
    similarity_hits_by_record_id: Optional[Dict[str, List[Dict[str, Any]]]] = None


@dataclass
class RowErrorSpec:
    prior_issues: List[Issue]
    dim_statuses: dict
    failed_checker: Optional[BaseChecker]
    checkers_after: List[BaseChecker]
    rules: dict


class QualityPipeline:
    def __init__(self, settings: dict = None, embedder: Embedder = None):
        self.settings = settings or load_settings()
        self.rules = self.settings["rules"]
        checker_cfg = self.rules.get("checkers", {})

        self._checkers: List[BaseChecker] = []
        if checker_cfg.get("format", True):
            self._checkers.append(FormatChecker())
        if checker_cfg.get("compliance", True):
            self._checkers.append(ComplianceChecker())
        if checker_cfg.get("duplicate", True):
            self._checkers.append(DuplicateChecker())
        if checker_cfg.get("conflict", True):
            self._checkers.append(ConflictChecker())
        if checker_cfg.get("semantic", True):
            self._checkers.append(SemanticChecker())

        self._vector_store = create_chroma_store(self.settings)
        self._embedder = embedder or create_embedder(self.settings)
        self._llm: Optional[LLMClient] = None
        need_llm = checker_cfg.get("llm_semantic") or (
            checker_cfg.get("llm_dup_conflict", False)
            and (checker_cfg.get("duplicate") or checker_cfg.get("conflict"))
        )
        if need_llm:
            sem_cfg = self.rules.get("semantic", {})
            llm_settings = {
                **self.settings,
                "llm_max_tokens": int(sem_cfg.get("llm_max_tokens", 256)),
            }
            self._llm = create_llm(llm_settings)

    def run_file(self, excel_path: Path, seed_production: bool = False) -> BatchReport:
        from backend.knowledge_qc.loaders.excel_loader import load_question_sheet

        records, _ = load_question_sheet(
            excel_path, self.rules, self.rules.get("id", {})
        )
        return self.run_records(records, QuestionRunOpts(seed_production=seed_production))

    def run_records(
        self,
        records: List[QARecord],
        opts: Optional[QuestionRunOpts] = None,
    ) -> BatchReport:
        run_opts = opts or QuestionRunOpts()
        seed_production = run_opts.seed_production
        on_progress = run_opts.on_progress
        on_log = run_opts.on_log
        checkpoint_interval = run_opts.checkpoint_interval
        on_checkpoint = run_opts.on_checkpoint
        should_cancel = run_opts.should_cancel
        row_indices = run_opts.row_indices
        retry_mode = run_opts.retry_mode
        similarity_hits_by_record_id = run_opts.similarity_hits_by_record_id
        total_all = len(records)

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        qc_records = filter_by_row_range(records, self.rules)
        if row_indices:
            qc_records = [r for r in qc_records if r.row_index in row_indices]
            row_list = ", ".join(str(x) for x in sorted(row_indices))
            log(f"续检模式：按行号 {row_list} 过滤后待检 {len(qc_records)} 条")
        total_qc = len(qc_records)
        if total_all and total_qc == 0:
            log("指定行范围内无相似问，已跳过质检")
            return BatchReport(total=0, passed=0, failed=0, errors=0, results=[])

        mode = self.rules.get("detection_mode", {})
        batch_on = mode.get("batch", True)
        prod_on = mode.get("production", True)
        scope_parts = []
        if batch_on:
            scope_parts.append("本批 staging")
        if prod_on:
            scope_parts.append("生产库 production")
        log(format_row_range_log(self.rules, total_qc, total_all))
        log(
            f"相似检索范围: "
            f"{' + '.join(scope_parts) if scope_parts else '无'}"
        )
        chk = self.rules.get("checkers", {})
        enabled_labels = []
        if chk.get("format"):
            enabled_labels.append("格式规范")
        if chk.get("compliance"):
            enabled_labels.append("合规安全")
        if chk.get("duplicate"):
            enabled_labels.append("重复检测")
        if chk.get("conflict"):
            enabled_labels.append("冲突检测")
        if chk.get("semantic"):
            enabled_labels.append("语义质量")
        log(f"已启用检测项: {', '.join(enabled_labels) if enabled_labels else '无'}")
        dup_conflict_llm = bool(
            chk.get("llm_dup_conflict")
            and (chk.get("duplicate") or chk.get("conflict"))
        )
        if dup_conflict_llm:
            log("重复/冲突 LLM 裁决: 开")
        elif chk.get("duplicate") or chk.get("conflict"):
            log("重复/冲突 LLM 裁决: 关（仅向量阈值）")
        vector_check_on = bool(chk.get("duplicate")) or bool(chk.get("conflict"))
        batch_staging = batch_on and vector_check_on

        if retry_mode:
            log("续检模式：复用已有向量库，跳过全表 Embedding 与 staging 重建")
        else:
            log("清空本批 staging…")
            if is_cancelled(should_cancel):
                log("用户已停止质检")
                return BatchReport(total=0, passed=0, failed=0, errors=0, results=[])
            self._vector_store.clear_staging()
        ctx = CheckContext(
            rules=self.rules,
            embedder=self._embedder,
            vector_store=self._vector_store,
            llm=self._llm,
            on_log=log,
            wordlist_overrides=dict(self.settings.get('wordlist_overrides') or {}),
        )

        if vector_check_on:
            if is_cancelled(should_cancel):
                log("用户已停止质检")
                return BatchReport(total=0, passed=0, failed=0, errors=0, results=[])
            if retry_mode:
                ctx.embedding_cache = {}
                prior_hits = restore_similarity_hits_cache(
                    similarity_hits_by_record_id or {}
                )
                if prior_hits:
                    ctx.similarity_hits_cache = prior_hits
                    ctx.similarity_hits_preloaded = True
                    log(
                        "续检模式：复用首次重复/冲突预查结果（"
                        f"{len(prior_hits)} 条语料，"
                        f"共 {count_similarity_hits(prior_hits)} 个命中）"
                    )
                else:
                    log("续检模式：未找到预查缓存，对异常行重新向量预查…")
                    preload_similarity_hits(
                        qc_records,
                        ctx,
                        query_batch_size=similarity_query_batch_size(self.settings),
                        on_log=log,
                        should_cancel=should_cancel,
                    )
            else:
                log("批量 Embedding（整表去重）…")
                ctx.embedding_cache = preload_embedding_cache(
                    self._embedder, [r.question for r in records]
                )
                log(
                    f"  完成：{total_all} 条语料，"
                    f"{len(ctx.embedding_cache)} 个 distinct 向量"
                )
                if batch_staging:
                    log(f"批量写入本批 staging（共 {total_all} 条）…")
                    self._index_to_collection(
                        records, self._vector_store.staging, "batch", ctx=ctx
                    )
                log(f"批量向量检索（重复/冲突预查，{total_qc} 条）…")
                preload_similarity_hits(
                    qc_records,
                    ctx,
                    query_batch_size=similarity_query_batch_size(self.settings),
                    on_log=log,
                    should_cancel=should_cancel,
                )
            if is_cancelled(should_cancel):
                log("用户已停止质检")
                return BatchReport(total=0, passed=0, failed=0, errors=0, results=[])

        if not retry_mode and seed_production and self._vector_store.production.count() == 0:
            self._index_to_collection(records, self._vector_store.production, "seed")

        worker_count = qc_worker_count(self.settings)
        if worker_count > 1:
            log(f"并发逐行质检（worker={worker_count}）…")
            results = self._run_checks_parallel(
                qc_records,
                ctx,
                worker_count,
                on_progress=on_progress,
                on_log=log,
                checkpoint_interval=checkpoint_interval,
                on_checkpoint=on_checkpoint,
                should_cancel=should_cancel,
            )
        else:
            results = self._run_checks_serial(
                qc_records,
                ctx,
                on_progress=on_progress,
                on_log=log,
                checkpoint_interval=checkpoint_interval,
                on_checkpoint=on_checkpoint,
                should_cancel=should_cancel,
            )

        if is_cancelled(should_cancel):
            log(f"已停止，完成 {len(results)}/{total_qc} 条")

        passed = sum(1 for r in results if r.verdict == Verdict.PASS)
        failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
        errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
        log(f"检测结束：通过 {passed}，不通过 {failed}，异常 {errors}")

        hits_cache = (
            dict(ctx.similarity_hits_cache)
            if vector_check_on and ctx.similarity_hits_preloaded
            else {}
        )
        return BatchReport(
            total=len(results),
            passed=passed,
            failed=failed,
            errors=errors,
            results=results,
            similarity_hits_by_record_id=hits_cache,
        )

    def _run_checks_serial(
        self,
        records: List[QARecord],
        ctx: CheckContext,
        *,
        on_progress: Callable[[int, int, str], None] | None,
        on_log: Callable[[str], None] | None,
        checkpoint_interval: int,
        on_checkpoint: Callable[[BatchReport], None] | None,
        should_cancel: Callable[[], bool] | None = None,
    ) -> List[CheckResult]:
        total = len(records)
        results: List[CheckResult] = []
        log_stride = max(1, total // 50) if total > 100 else 1

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        for i, record in enumerate(records):
            if is_cancelled(should_cancel):
                break
            if on_progress:
                on_progress(i + 1, total, record.question)
            result = self.check(record, ctx)
            results.append(result)
            failed = [x for x in result.issues if not x.passed]
            should_log = (
                failed
                or result.verdict != Verdict.PASS
                or i == 0
                or i + 1 == total
                or (i + 1) % log_stride == 0
            )
            if should_log:
                self._log_row_result(i + 1, total, record, result, log)
            maybe_emit_checkpoint(
                results,
                i + 1,
                checkpoint_interval,
                on_checkpoint,
                build_batch_report,
            )
            if on_checkpoint and checkpoint_interval > 0 and (i + 1) % checkpoint_interval == 0:
                log(f"  已增量写入 {i + 1}/{total} 条质检结果")
        return results

    def _run_checks_parallel(
        self,
        records: List[QARecord],
        ctx: CheckContext,
        worker_count: int,
        *,
        on_progress: Callable[[int, int, str], None] | None,
        on_log: Callable[[str], None] | None,
        checkpoint_interval: int,
        on_checkpoint: Callable[[BatchReport], None] | None,
        should_cancel: Callable[[], bool] | None = None,
    ) -> List[CheckResult]:
        total = len(records)
        results: List[Optional[CheckResult]] = [None] * total
        done = 0
        lock = Lock()

        def log(msg: str) -> None:
            if on_log:
                on_log(msg)

        def on_one_finished(index: int, record: QARecord, result: CheckResult) -> None:
            nonlocal done
            with lock:
                results[index] = result
                done += 1
                current = done
                snapshot = [r for r in results if r is not None]
            if on_progress:
                on_progress(current, total, record.question)
            self._log_row_result(current, total, record, result, log)
            maybe_emit_checkpoint(
                snapshot,
                current,
                checkpoint_interval,
                on_checkpoint,
                build_batch_report,
            )
            if on_checkpoint and checkpoint_interval > 0 and current % checkpoint_interval == 0:
                log(f"  已增量写入 {current}/{total} 条质检结果")

        with create_qc_thread_pool(worker_count) as pool:
            futures = {
                pool.submit(self.check, record, ctx): i
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
                    result = _row_error_result(
                        record,
                        e,
                        RowErrorSpec(
                            prior_issues=[],
                            dim_statuses={},
                            failed_checker=None,
                            checkers_after=[],
                            rules=self.rules,
                        ),
                    )
                on_one_finished(index, record, result)

        return [r for r in results if r is not None]  # type: ignore[misc]

    @staticmethod
    def _log_row_result(
        current: int,
        total: int,
        record: QARecord,
        result: CheckResult,
        log: Callable[[str], None],
    ) -> None:
        row = format_row_ref(record.row_index)
        log(f"[{current}/{total}] {row} {result.verdict.value} | {record.question}")
        failed = [x for x in result.issues if not x.passed]
        for issue in failed[:3]:
            log(f"    └ [{issue.dimension}] {issue.reason}")

    def check(self, record: QARecord, ctx: CheckContext) -> CheckResult:
        all_issues: List[Issue] = []
        dim_statuses: dict = {}
        checkers = self._checkers
        for i, checker in enumerate(checkers):
            try:
                issues = checker.check(record, ctx)
            except Exception as e:
                return _row_error_result(
                    record,
                    e,
                    RowErrorSpec(
                        prior_issues=all_issues,
                        dim_statuses=dim_statuses,
                        failed_checker=checker,
                        checkers_after=checkers[i + 1:],
                        rules=self.rules,
                    ),
                )
            all_issues.extend(issues)
            dim = checker.dimension
            if issues:
                dim_statuses[dim] = DIMENSION_STATUS_FAIL
            else:
                dim_statuses[dim] = DIMENSION_STATUS_PASS

        verdict, action = _aggregate(all_issues)
        return CheckResult(
            record=record,
            verdict=verdict,
            issues=all_issues,
            final_action=action,
            dimension_statuses=dim_statuses,
        )

    def _index_to_collection(
        self, records, collection, source: str, ctx: CheckContext = None
    ) -> None:
        if not records:
            return
        if ctx:
            embeddings = [ctx.get_embedding(r.question) for r in records]
        else:
            embeddings = self._embedder.embed([r.question for r in records])
        self._vector_store.upsert_records(collection, records, embeddings, source)


def _row_error_result(
    record: QARecord,
    exc: BaseException,
    spec: RowErrorSpec,
) -> CheckResult:
    if spec.failed_checker is not None:
        spec.dim_statuses[spec.failed_checker.dimension] = DIMENSION_STATUS_FAIL
        mark_dimensions_skipped(spec.dim_statuses, spec.checkers_after, spec.rules)
    issues = list(spec.prior_issues)
    reason = _format_row_error(exc)
    issues.append(
        Issue(
            dimension="质检过程",
            rule_id="QC_ROW_ERROR",
            severity="high",
            reason=reason,
            suggestion="请检查 LLM/Embedding 配置或网络后重检本条",
        )
    )
    return CheckResult(
        record=record,
        verdict=Verdict.ERROR,
        issues=issues,
        final_action=Verdict.ERROR.value,
        dimension_statuses=spec.dim_statuses,
    )


def _format_row_error(exc: BaseException) -> str:
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


def _aggregate(issues: List[Issue]) -> tuple:
    failed = [i for i in issues if not i.passed]
    if failed:
        dims = "、".join({i.dimension for i in failed})
        return Verdict.FAIL, f"不通过：{dims}，请按建议修改后重检"
    return Verdict.PASS, "可入库"
