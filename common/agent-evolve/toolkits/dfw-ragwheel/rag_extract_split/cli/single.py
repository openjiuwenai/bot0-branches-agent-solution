#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import argparse
import heapq
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from rag_extract_split.common.helpers import append_pretty_json_block, log_dir, now_ms, truncate
from rag_extract_split.io.data_io import load_badcases_from_excel
from rag_extract_split.infrastructure.embedding import embed_texts
from rag_extract_split.generation.llm import llm_config, normalize_llm_content, run_llm_completion
from rag_extract_split.config.settings import CONFIG


class _MinHeapCollector:
    """小根堆跨轮搜集器：容量 m，保存与 Q 相似度最高的相似问。"""

    def __init__(self, capacity: int, threshold: float) -> None:
        self.capacity = max(1, int(capacity))
        self.threshold = float(threshold)
        # (similarity, question)；堆顶为当前已收录中的最低相似度
        self._heap: List[Tuple[float, str]] = []
        self._seen: set[str] = set()

    def __len__(self) -> int:
        return len(self._heap)

    def is_full(self) -> bool:
        return len(self._heap) >= self.capacity

    def top_similarity(self) -> float:
        return self._heap[0][0] if self._heap else float("-inf")

    def _accept_bar(self) -> float:
        """准入门槛：max(t, sim(H.top, Q))"""
        if not self._heap:
            return self.threshold
        return max(self.threshold, self.top_similarity())

    def try_add(self, question: str, similarity: float) -> bool:
        q = (question or "").strip()
        if not q or q in self._seen:
            return False

        sim = float(similarity)
        bar = self._accept_bar()
        if sim < bar:
            return False

        if not self.is_full():
            heapq.heappush(self._heap, (sim, q))
            self._seen.add(q)
            return True

        # 堆已满：仅当优于堆顶（当前最差）时替换
        min_sim, min_q = self._heap[0]
        if sim <= min_sim:
            return False
        heapq.heapreplace(self._heap, (sim, q))
        self._seen.discard(min_q)
        self._seen.add(q)
        return True

    def items_desc(self) -> List[Tuple[float, str]]:
        return sorted(self._heap, key=lambda x: x[0], reverse=True)


def _pick_cols(rows: Sequence[Dict[str, Any]], q_col: str, a_col: str) -> List[Tuple[str, str]]:
    pairs: List[Tuple[str, str]] = []
    q_col_l = (q_col or "query").strip().lower()
    a_col_l = (a_col or "answer").strip().lower()
    q_alias = [q_col_l, "query", "q", "question", "用户问题", "问题"]
    a_alias = [a_col_l, "answer", "a", "回复", "答案"]
    for r in rows or []:
        if not isinstance(r, dict):
            continue
        qv = ""
        av = ""
        for k in q_alias:
            if k in r and str(r.get(k) or "").strip():
                qv = str(r.get(k) or "").strip()
                break
        if not qv:
            for kk, vv in r.items():
                if str(kk).strip().lower() in q_alias and str(vv or "").strip():
                    qv = str(vv or "").strip()
                    break
        for k in a_alias:
            if k in r and str(r.get(k) or "").strip():
                av = str(r.get(k) or "").strip()
                break
        if not av:
            for kk, vv in r.items():
                if str(kk).strip().lower() in a_alias and str(vv or "").strip():
                    av = str(vv or "").strip()
                    break
        if qv:
            pairs.append((qv, av))
    return pairs


def _cosine_sim_batch(q_vec: Sequence[float], mat: Sequence[Sequence[float]]) -> List[float]:
    q = np.asarray(list(q_vec), dtype=np.float32)
    m = np.asarray([list(v) for v in mat], dtype=np.float32)
    qn = float(np.linalg.norm(q) + 1e-12)
    mn = np.linalg.norm(m, axis=1) + 1e-12
    sims = (m @ q) / (mn * qn)
    return [float(x) for x in sims.tolist()]


def _build_messages_for_similar_questions(q: str, k: int) -> List[Dict[str, str]]:
    system = (
        "你是相似问生成器。给定用户原始问题 Q，请生成语义等价或高度相关、但表述多样的相似问。\n"
        "改写规则示例：\n"
        "- 保留核心实体（人名、金额、业务对象等）\n"
        "- 保持意图类型不变（查询/办理/咨询等）\n"
        "- 可对多轮对话语境做提纯，生成单轮独立问法\n"
        "- 可做语序调整与同义替换\n"
        "输出要求：\n"
        "- 只输出 JSON\n"
        "- 顶层仅包含 questions 数组\n"
        f"- questions 长度必须严格等于 {int(k)}\n"
        "- 每个元素是字符串\n"
        "- 不要输出与 Q 完全相同的文本\n"
        "- 避免重复、避免只改虚词\n"
    )
    user = {"task": "single_mode_similar_questions", "q": q, "k": int(k), "output_schema": {"questions": ["..."]}}
    return [{"role": "system", "content": system}, {"role": "user", "content": json.dumps(user, ensure_ascii=False)}]


def _parse_questions_from_content(content: str) -> List[str]:
    obj = json.loads(content)
    qs = obj.get("questions") if isinstance(obj, dict) else None
    if not isinstance(qs, list):
        raise ValueError("missing questions")
    out: List[str] = []
    seen = set()
    for x in qs:
        s = str(x or "").strip()
        if not s:
            continue
        if s in seen:
            continue
        seen.add(s)
        out.append(s)
    return out


def _llm_generate_similar_questions(q: str, k: int, llm_config_name: Optional[str] = None) -> List[str]:
    cfg = llm_config(llm_config_name)
    messages = _build_messages_for_similar_questions(q, k)
    extra_body: Dict[str, Any] = {"enable_thinking": False}
    trace_enabled = bool(CONFIG.get("rag_extract", {}).get("llm_trace_enabled", True))
    trace_file = str(CONFIG.get("logging", {}).get("llm_trace_file") or "rag_extract_llm_trace.log")
    trace_path = log_dir() / trace_file
    trace_base: Dict[str, Any] = {
        "kind": "single_mode_llm_call",
        "meta": {"ts_ms": now_ms(), "model": str(cfg.get("model") or ""), "base_url": str(cfg.get("base_url") or ""), "request_mode": str(cfg.get("request_mode") or "")},
        "input": {"q": truncate(q, 800), "k": int(k), "messages": list(messages), "extra_body": dict(extra_body)},
    }
    try:
        completion = run_llm_completion(cfg=cfg, model=str(cfg["model"]), messages=messages, extra_body=extra_body)
        if isinstance(completion, dict):
            raw_content = str(completion.get("raw_content") or "").strip()
            usage = completion.get("usage")
            raw_response = completion.get("raw_response")
        else:
            raw_content = (completion.choices[0].message.content or "").strip()
            usage = None
            raw_response = None
        if not raw_content:
            raise RuntimeError("模型返回为空")
        content = normalize_llm_content(raw_content)
        questions = _parse_questions_from_content(content)
        if trace_enabled:
            append_pretty_json_block(
                trace_path,
                {
                    **trace_base,
                    "output": {"raw_content": raw_content, "parsed_questions": questions, "usage": usage, "raw_response": raw_response},
                    "error": None,
                },
            )
        return questions
    except Exception as e:
        if trace_enabled:
            append_pretty_json_block(trace_path, {**trace_base, "output": None, "error": str(e)})
        raise


def _sanitize_question_list(qs: Sequence[str], original_q: str) -> List[str]:
    oq = (original_q or "").strip()
    out: List[str] = []
    seen = set()
    for s in qs:
        t = re.sub(r"\s+", " ", str(s or "").strip())
        if not t:
            continue
        if oq and t == oq:
            continue
        if t in seen:
            continue
        seen.add(t)
        out.append(t)
    return out


def process_one_q(
    *,
    q: str,
    a: str,
    k: int,
    m: int,
    threshold: float,
    max_attempts: int,
    llm_config_name: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """小根堆跨轮搜集：每轮 LLM 生成 k 条候选，按 sim(q,Q) 过滤后写入容量 m 的小根堆。"""
    q0 = (q or "").strip()
    a0 = (a or "").strip()
    if not q0:
        return []

    m = max(1, int(m))
    k = int(k)
    if k <= 0:
        k = max(5, 2 * m)
    else:
        k = max(k, 2 * m)
    threshold = float(threshold)
    max_step = max(1, int(max_attempts))

    q_vec = embed_texts([q0])[0]
    heap = _MinHeapCollector(capacity=m, threshold=threshold)

    step = 0
    while not heap.is_full() and step < max_step:
        step += 1
        cand = _llm_generate_similar_questions(q0, k, llm_config_name)
        cand = _sanitize_question_list(cand, q0)
        if not cand:
            continue

        vecs = embed_texts(cand)
        sims = _cosine_sim_batch(q_vec, vecs)
        for qq, sim in zip(cand, sims):
            heap.try_add(qq, sim)

    rows: List[Dict[str, Any]] = []
    for rank, (sim, gen_q) in enumerate(heap.items_desc(), start=1):
        rows.append(
            {
                "source_q": q0,
                "source_a": a0,
                "gen_q": gen_q,
                "similarity": float(sim),
                "threshold": float(threshold),
                "gen_rank": int(rank),
                "m_target": int(m),
                "k_per_round": int(k),
                "steps_used": int(step),
                "max_step": int(max_step),
                "heap_full": bool(heap.is_full()),
            }
        )
    return rows


def load_pairs(path: Path, sheet: Optional[str], q_column: str, a_column: str) -> List[Tuple[str, str]]:
    rows = load_badcases_from_excel(path, sheet)
    return _pick_cols(rows, q_column, a_column)


def write_csv(rows: Sequence[Dict[str, Any]], out_path: Path) -> None:
    import pandas as pd

    out_path.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(list(rows)).to_csv(out_path, index=False, encoding="utf-8-sig")


def run_single_pipeline(
    *,
    input_path: Path,
    output_path: Path,
    sheet: Optional[str],
    q_column: str,
    a_column: str,
    k: int,
    m: int,
    threshold: float,
    max_attempts: int,
    llm_trace_file: str = "",
    llm_config_name: Optional[str] = None,
) -> int:
    """供 `python -m rag_extract_split.cli.single` 与 `main.py single` 共用。"""
    if llm_trace_file:
        CONFIG.setdefault("logging", {})["llm_trace_file"] = str(llm_trace_file)

    if not input_path.is_file():
        print(f"文件不存在: {input_path}")
        return 2

    pairs = load_pairs(input_path, sheet, str(q_column), str(a_column))
    if not pairs:
        print("未读到有效输入行")
        return 2

    all_rows: List[Dict[str, Any]] = []
    for idx, (q, a) in enumerate(pairs, start=1):
        try:
            rows = process_one_q(q=q, a=a, k=k, m=m, threshold=threshold, max_attempts=max_attempts, llm_config_name=llm_config_name)
            all_rows.extend(rows)
            print(f"[{idx}/{len(pairs)}] done rows={len(rows)} q={q[:60]}")
        except Exception as e:
            all_rows.append(
                {"source_q": q, "source_a": a, "error": str(e), "k": int(k), "m_target": int(m), "threshold": float(threshold)}
            )
            print(f"[{idx}/{len(pairs)}] error={e} q={q[:60]}")

    write_csv(all_rows, output_path)
    print(f"output={output_path} rows={len(all_rows)}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="single 模式：逐行生成相似问并做相似度阈值筛选后导出 CSV")
    ap.add_argument("--input", required=True, type=Path, help="输入 CSV/Excel：两列（用户问题/答案）")
    ap.add_argument("--sheet", default=None, help="Excel sheet 名或索引（CSV 可忽略）")
    ap.add_argument("--q-column", default="query", help="问题列名（默认 query）")
    ap.add_argument("--a-column", default="answer", help="答案列名（默认 answer）")
    ap.add_argument("--output", required=True, type=Path, help="输出 CSV 路径")
    ap.add_argument("--k", type=int, default=0, help="每轮 LLM 生成候选数 k（默认 max(5, 2m)）")
    ap.add_argument("--m", type=int, default=2, help="小根堆目标收集数量 m")
    ap.add_argument("--threshold", type=float, default=0.80, help="相似度阈值 t（余弦相似度）")
    ap.add_argument("--max-attempts", type=int, default=5, help="最大轮次 MaxStep")
    ap.add_argument("--llm-trace-file", default="", help="可选：覆盖 LLM 调用 trace 文件名（默认 logs/rag_extract_llm_trace.log）")
    args = ap.parse_args()
    return run_single_pipeline(
        input_path=Path(args.input),
        output_path=Path(args.output),
        sheet=args.sheet,
        q_column=str(args.q_column),
        a_column=str(args.a_column),
        k=int(args.k),
        m=int(args.m),
        threshold=float(args.threshold),
        max_attempts=int(args.max_attempts),
        llm_trace_file=str(args.llm_trace_file or ""),
    )


if __name__ == "__main__":
    raise SystemExit(main())

