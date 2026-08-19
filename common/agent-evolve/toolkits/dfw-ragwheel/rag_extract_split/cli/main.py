#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path
from typing import Any, Dict, List, Sequence, Tuple

from rag_extract_split.infrastructure.chroma_store import (
    chroma_hnsw_space,
    clear_collection_fully,
    delete_ids,
    drop_collection,
    get_by_ids,
    get_chroma_client,
    query_topk,
    upsert_cases,
)
from rag_extract_split.io.data_io import load_badcases_from_excel, write_frozen_qa_xlsx
from rag_extract_split.extraction.engine import run_extract
from rag_extract_split.config.models import RAGCase
from rag_extract_split.config.settings import CONFIG
from rag_extract_split.cli.single import run_single_pipeline
from rag_extract_split.config.llm_registry import get_llm_config
from rag_extract_split.config.embedding_manager import set_active_embedding_config
from rag_extract_split.cli_logger import log_cli_execution


def _parse_sheet(sheet: str | None) -> str | int | None:
    if sheet is None:
        return None
    try:
        return int(sheet)
    except ValueError:
        return sheet


def _load_llm_config_file(path: str | Path) -> Dict[str, Any]:
    """加载 LLM 配置文件（JSON/YAML），返回 rag_llm 层级的配置字典。"""
    p = Path(path)
    raw = p.read_text(encoding="utf-8")
    ext = p.suffix.lower()
    if ext in (".yaml", ".yml"):
        import yaml

        data = yaml.safe_load(raw)
    else:
        data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError(f"LLM 配置文件内容必须是字典: {path}")
    return data


def _apply_cli_overrides(args: argparse.Namespace) -> None:
    if getattr(args, "chroma_space", None):
        CONFIG.setdefault("chroma", {})["hnsw_space"] = str(args.chroma_space)
    if getattr(args, "completion_mode", None):
        rag_cfg = CONFIG.setdefault("rag_extract", {})
        rag_cfg["completion_mode"] = str(args.completion_mode)
    chroma_cfg = CONFIG.setdefault("chroma", {})
    if getattr(args, "write_batch_size", None) is not None:
        chroma_cfg["write_batch_size"] = max(1, int(args.write_batch_size))
    if getattr(args, "query_batch_size", None) is not None:
        chroma_cfg["query_batch_size"] = max(1, int(args.query_batch_size))
    if getattr(args, "delete_batch_size", None) is not None:
        chroma_cfg["delete_batch_size"] = max(1, int(args.delete_batch_size))
    llm_config_path = getattr(args, "llm_config", None)
    if llm_config_path:
        overrides = _load_llm_config_file(llm_config_path)
        CONFIG.setdefault("rag_llm", {}).update(overrides)
    llm_config_name = getattr(args, "llm_config_name", None)
    if llm_config_name:
        CONFIG["rag_llm"] = get_llm_config(str(llm_config_name))
    embedding_config_name = getattr(args, "embedding_config_name", None)
    if embedding_config_name:
        set_active_embedding_config(str(embedding_config_name).strip())


def _build_source_namespace(path: Path, sheet: str | int | None) -> str:
    stem = re.sub(r"[^0-9A-Za-z_\-]+", "_", path.stem).strip("_") or "excel"
    raw = f"{path.resolve()}|sheet={sheet}"
    short = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:10]
    return f"{stem}_{short}"


def _import_qa_to_collection(
    *,
    excel_path: Path,
    sheet: str | int | None,
    collection: str,
    replace: bool,
    batch_size: int,
) -> Tuple[int, List[str]]:
    rows = load_badcases_from_excel(excel_path, sheet)
    if not rows:
        raise RuntimeError("导入失败：未解析到有效 QA（需非空 query/answer）")
    if replace:
        clear_collection_fully(collection)
    ns = _build_source_namespace(excel_path, sheet)
    cases = [
        RAGCase(
            case_id=f"{ns}:{str(r.get('id') or i)}",
            query=str(r.get("query") or "").strip(),
            answer=str(r.get("answer") or "").strip(),
            metadata={
                "source": "rag_extract_cli_import",
                "source_file": excel_path.name,
                "source_path": str(excel_path),
                "source_sheet": "" if sheet is None else str(sheet),
                "source_namespace": ns,
            },
        )
        for i, r in enumerate(rows)
        if str(r.get("query") or "").strip() and str(r.get("answer") or "").strip()
    ]
    ids = [c.case_id for c in cases]
    bs = max(1, int(batch_size))
    written = upsert_cases(collection, cases, batch_size=bs)
    return written, ids


def _write_ids_output(ids: Sequence[str], output_path: Path, collection: str | None = None) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    suffix = output_path.suffix.lower()
    if suffix in (".xlsx", ".xls"):
        import pandas as pd

        df_data: dict[str, list] = {"id": list(ids)}
        if collection:
            df_data["collection"] = [str(collection)] * len(ids)
            # 将 collection 列放在前面，便于阅读
            df_data = {"collection": df_data["collection"], "id": df_data["id"]}
        pd.DataFrame(df_data).to_excel(output_path, index=False, engine="openpyxl")
    elif suffix == ".json":
        payload: dict = {"ids": list(ids)}
        if collection:
            payload["collection"] = str(collection)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    else:
        with open(output_path, "w", encoding="utf-8") as f:
            if collection:
                f.write("collection,id\n")
                for cid in ids:
                    f.write(f"{collection},{cid}\n")
            else:
                f.write("id\n")
                for cid in ids:
                    f.write(f"{cid}\n")


def _read_ids_from_file(path: Path, sheet: str | int | None, id_column: str) -> List[str]:
    import pandas as pd

    if path.suffix.lower() == ".csv":
        df = pd.read_csv(path, dtype=str, encoding="utf-8-sig", engine="python")
    else:
        df = pd.read_excel(path, dtype=str, sheet_name=sheet or 0, engine="openpyxl")
    lookup = {str(c).strip().lower(): c for c in df.columns.tolist()}
    id_col = lookup.get(str(id_column).strip().lower())
    if id_col is None:
        raise RuntimeError(f"delete 输入文件缺少 id 列: {id_column}")
    ids: List[str] = []
    seen = set()
    for v in df[id_col].tolist():
        cid = str(v or "").strip()
        if cid and cid not in seen:
            seen.add(cid)
            ids.append(cid)
    return ids


def _run_extract(args: argparse.Namespace) -> int:
    _apply_cli_overrides(args)
    excel_path = args.excel
    if not excel_path.is_file():
        print(f"文件不存在: {excel_path}")
        return 2
    do_import = bool(args.import_only or args.import_excel is not None)
    if do_import:
        import_path = Path(args.import_excel) if args.import_excel is not None else excel_path
        if not import_path.is_file():
            print(f"导入文件不存在: {import_path}")
            return 2
        import_sheet = _parse_sheet(args.import_sheet if args.import_sheet is not None else args.sheet)
        import_collection = str(args.import_collection or args.target_kb)
        try:
            n, imported_ids = _import_qa_to_collection(
                excel_path=import_path,
                sheet=import_sheet,
                collection=import_collection,
                replace=bool(args.import_replace),
                batch_size=int(args.import_batch_size),
            )
            print(f"import_done collection={import_collection} file={import_path.name} written={n} replace={'yes' if args.import_replace else 'no'}")
            if getattr(args, "import_export_ids", ""):
                ids_out = Path(args.import_export_ids)
                _write_ids_output(imported_ids, ids_out, import_collection)
                print(f"import_ids_out={ids_out} ids={len(imported_ids)}")
        except Exception as e:
            print(f"导入失败: {e}")
            return 2
        if args.import_only:
            return 0

    badcases = load_badcases_from_excel(excel_path, _parse_sheet(args.sheet))
    if not badcases:
        print("未解析到有效 BadCase（需同时非空的 query 与 answer 列）")
        return 2
    pre_task_id = "EXT-" + uuid.uuid4().hex[:10].upper()
    out_xlsx = args.out if args.out is not None else Path(f"extracted_qa_{pre_task_id}.xlsx")
    if not args.quiet:
        print(f"collection 启动清理: {'开启' if args.clear_collection else '关闭（默认）'}")
        print(f"知识补全模式: {args.completion_mode}")
    res = run_extract(
        badcases=badcases,
        rule_text=args.rule_text,
        rule_label=args.rule_label,
        init_count=max(1, int(args.init_count)),
        target_kb=args.target_kb,
        clear_collection_on_start=bool(args.clear_collection),
        verbose=not args.quiet,
        task_id=pre_task_id,
        frozen_qa_snapshot_xlsx=out_xlsx,
        llm_config_name=getattr(args, "llm_config_name", None) or None,
    )
    pairs = list(res.final_qa_pairs or [])
    write_frozen_qa_xlsx(out_xlsx, pairs)
    print(f"task_id={res.task_id} status={res.status} collection={res.collection} badcases={len(badcases)} qa_pairs={len(pairs)} out={out_xlsx}")
    if args.json_out:
        meta = {
            "task_id": res.task_id,
            "status": res.status,
            "target_kb": res.target_kb,
            "collection": res.collection,
            "chroma_hnsw_space": chroma_hnsw_space(),
            "last_error": res.last_error,
            "iterations": [{"round": it.round_num, "qaCount": it.qa_count, "recallRate": it.recall_rate, "ts_ms": it.ts_ms} for it in res.iterations],
            "round_logs": res.round_logs,
        }
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        print(f"json={args.json_out}")
    return 0 if res.status == "success" else 5


def _run_import(args: argparse.Namespace) -> int:
    _apply_cli_overrides(args)
    path = Path(args.input)
    if not path.is_file():
        print(f"文件不存在: {path}")
        return 2
    n, imported_ids = _import_qa_to_collection(
        excel_path=path,
        sheet=_parse_sheet(args.sheet),
        collection=str(args.collection),
        replace=bool(args.replace),
        batch_size=int(args.batch_size),
    )
    print(f"import_done collection={args.collection} file={path.name} written={n} replace={'yes' if args.replace else 'no'}")
    if args.export_ids:
        out = Path(args.export_ids)
        _write_ids_output(imported_ids, out, str(args.collection))
        print(f"import_ids_out={out} ids={len(imported_ids)}")
    return 0


def _run_query(args: argparse.Namespace) -> int:
    single_q = str(args.input_single or "").strip()
    if single_q:
        # 单条 query 模式
        print(f"query: {single_q}")
        print(f"collection: {args.collection}\n")
        result = query_topk(str(args.collection), [single_q], int(args.top_k), batch_size=int(args.batch_size))
        for r in result:
            print(f"query: {r['query']}")
            print("top-k hits:")
            metas = r.get("metadatas") or []
            docs = r.get("documents") or []
            dists = r.get("distances") or []
            for i, (meta, doc, dist) in enumerate(zip(metas, docs, dists), 1):
                intent = str((meta or {}).get("answer") or "") if isinstance(meta, dict) else ""
                print(f"  [{i}] intent: {intent:<12} distance: {dist:.4f}  doc: {doc}")
        return 0

    # 批量文件模式
    if not args.input:
        print("query 失败：请提供 --input 或 --input-single")
        return 2
    path = Path(args.input)
    if not path.is_file():
        print(f"文件不存在: {path}")
        return 2
    rows = load_badcases_from_excel(path, _parse_sheet(args.sheet))
    qs = [str(r.get("query") or "").strip() for r in rows]
    ans = [str(r.get("answer") or "").strip() for r in rows]
    result = query_topk(str(args.collection), qs, int(args.top_k), batch_size=int(args.batch_size))
    details: List[Dict[str, Any]] = []
    hit = 0
    for i, row in enumerate(result):
        metas = row.get("metadatas") or []
        top_answers = [str((m or {}).get("answer") or "") if isinstance(m, dict) else "" for m in metas]
        ok = ans[i] in top_answers if i < len(ans) else False
        if ok:
            hit += 1
        details.append({"query": qs[i], "expected_answer": ans[i], "hit_topk": ok, "topk_answers": " | ".join(top_answers)})
    total = len(qs)
    print(f"query_done collection={args.collection} hit={hit}/{total} recall={(hit/total if total else 0.0):.4f}")
    if args.output:
        import pandas as pd

        pd.DataFrame(details).to_excel(Path(args.output), index=False, engine="openpyxl")
        print(f"output={args.output}")
    return 0


def _run_get(args: argparse.Namespace) -> int:
    ids = [s.strip() for s in str(args.ids).split(",") if s.strip()]
    res = get_by_ids(str(args.collection), ids, include=["documents", "metadatas"], batch_size=int(args.batch_size))
    out_ids = res.get("ids") or []
    docs = res.get("documents") or []
    metas = res.get("metadatas") or []
    print(f"get_done requested={len(ids)} hit={len(out_ids)}")
    for i, cid in enumerate(out_ids):
        item = {"id": cid, "query": docs[i] if i < len(docs) else "", "metadata": metas[i] if i < len(metas) else {}}
        print(json.dumps(item, ensure_ascii=False))
    return 0


def _run_list(args: argparse.Namespace) -> int:
    client = get_chroma_client()
    colls = client.list_collections()
    print(f"collections={len(colls)}")
    for c in colls:
        name = getattr(c, "name", str(c))
        if args.with_count:
            try:
                cnt = client.get_collection(name=name).count()
            except Exception:
                cnt = "unknown"
            print(f"- {name} (count={cnt})")
        else:
            print(f"- {name}")
    return 0


def _run_update(args: argparse.Namespace) -> int:
    _apply_cli_overrides(args)
    import pandas as pd

    path = Path(args.input)
    if not path.is_file():
        print(f"文件不存在: {path}")
        return 2
    if path.suffix.lower() == ".csv":
        df = pd.read_csv(path, dtype=str, encoding="utf-8-sig", engine="python")
    else:
        df = pd.read_excel(path, dtype=str, sheet_name=_parse_sheet(args.sheet) or 0, engine="openpyxl")
    cols = [str(c).strip().lower() for c in df.columns.tolist()]
    if "id" not in cols or "query" not in cols:
        print("update 输入至少包含 id/query 列")
        return 2
    id_col = df.columns[cols.index("id")]
    q_col = df.columns[cols.index("query")]
    a_col = df.columns[cols.index("answer")] if "answer" in cols else None
    cases: List[RAGCase] = []
    for _, row in df.iterrows():
        cid = str(row.get(id_col) or "").strip()
        q = str(row.get(q_col) or "").strip()
        a = str(row.get(a_col) or "").strip() if a_col else ""
        if cid and q:
            cases.append(RAGCase(case_id=cid, query=q, answer=a, metadata={"op": "update", "source_file": path.name}))
    n = upsert_cases(str(args.collection), cases, batch_size=int(args.batch_size))
    print(f"update_done collection={args.collection} input={len(cases)} upsert={n}")
    return 0




def _confirm_delete_collection(collection_name: str, yes: bool) -> bool:
    """询问用户是否删除整个 collection。"""
    if yes:
        return True
    try:
        answer = input(f'确定要删除整个 collection "{collection_name}" 吗？该操作不可恢复。[y/N] ')
    except (EOFError, KeyboardInterrupt):
        print("\n取消删除")
        return False
    return answer.strip().lower() in ('y', 'yes')


def _run_delete(args: argparse.Namespace) -> int:
    collection_name = str(args.collection).strip()

    # 如果未提供 ids 或 input，则删除整个 collection
    has_ids = bool(args.ids)
    has_input = bool(args.input)

    if not has_ids and not has_input:
        # 删除整个 collection
        if not _confirm_delete_collection(collection_name, bool(args.yes)):
            print("delete 取消")
            return 0
        try:
            drop_collection(collection_name)
            print(f"delete_done collection={collection_name} dropped=1")
            return 0
        except Exception as exc:
            print(f"delete 失败：{exc}")
            return 2

    # 按 id 删除
    ids: List[str] = []
    if args.ids:
        ids.extend([s.strip() for s in str(args.ids).split(",") if s.strip()])
    if args.input:
        file_ids = _read_ids_from_file(Path(args.input), _parse_sheet(args.sheet), str(args.id_column))
        ids.extend(file_ids)
    seen = set()
    final_ids: List[str] = []
    for cid in ids:
        if cid and cid not in seen:
            seen.add(cid)
            final_ids.append(cid)
    if not final_ids:
        print("delete 失败：请提供 --ids 或 --input + --id-column")
        return 2
    n = delete_ids(str(args.collection), final_ids, batch_size=int(args.batch_size))
    print(f"delete_done collection={args.collection} deleted={n}")
    return 0


def _run_single(args: argparse.Namespace) -> int:
    _apply_cli_overrides(args)
    return run_single_pipeline(
        input_path=Path(args.single_input),
        output_path=Path(args.single_output),
        sheet=getattr(args, "single_sheet", None),
        q_column=str(getattr(args, "single_q_column", "query")),
        a_column=str(getattr(args, "single_a_column", "answer")),
        k=int(args.single_k),
        m=int(args.single_m),
        threshold=float(args.single_threshold),
        max_attempts=int(args.single_max_attempts),
        llm_trace_file=str(getattr(args, "single_llm_trace_file", "") or ""),
        llm_config_name=getattr(args, "llm_config_name", None) or None,
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="rag_extract_split CLI（extract + CRUD + single）",
        epilog=(
            "相关命令示例:\n"
            "  python -m rag_extract_split.main extract --excel .\\badcase.xlsx --target-kb default\n"
            "  python -m rag_extract_split.main import --input .\\qa.xlsx --collection qa_excel_kb --replace\n"
            "  python -m rag_extract_split.main query --input .\\qa_eval.xlsx --collection qa_excel_kb --top-k 5\n"
            "  python -m rag_extract_split.main delete --collection qa_excel_kb --input .\\ids.xlsx --id-column id\n"
            "  python -m rag_extract_split.main single --single-input .\\qa.xlsx --single-output .\\out.csv\n"
            "查看子命令帮助:\n"
            "  python -m rag_extract_split.main <子命令> --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    sub = parser.add_subparsers(dest="command", required=False)

    p_extract = sub.add_parser(
        "extract",
        help="运行萃取流程（默认命令）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main extract --excel .\\badcase.xlsx --target-kb default\n"
            "  python -m rag_extract_split.main extract --excel .\\badcase.xlsx --import-only --import-replace\n"
            "  python -m rag_extract_split.main import --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_extract.add_argument("--excel", required=True, type=Path, help="BadCase Excel 路径（.xlsx/.xls/.csv）")
    p_extract.add_argument("--sheet", default=None, help="Excel sheet 名或索引（默认第一个 sheet）")
    p_extract.add_argument("--out", type=Path, default=None, help="输出 Excel 路径（默认 extracted_qa_<task_id>.xlsx）")
    p_extract.add_argument("--json-out", type=Path, default=None, help="可选：同时写出 JSON（含 task 元数据）")
    p_extract.add_argument("--target-kb", default="default", help="目标知识库标识（影响 Chroma collection 名）")
    p_extract.add_argument("--init-count", type=int, default=1, help="保留兼容参数")
    p_extract.add_argument("--rule-text", default="", help="业务规则全文（传给 LLM）")
    p_extract.add_argument("--rule-label", default="", help="规则标签（短）")
    p_extract.add_argument("--completion-mode", default=str(CONFIG.get("rag_extract", {}).get("completion_mode") or "llm"), choices=["llm", "cluster"])
    p_extract.add_argument("--llm-config-name", default=None, help="可选：使用已保存的 LLM 配置名称（默认使用 default）")
    p_extract.add_argument("--embedding-config-name", default=None, help="可选：使用已保存的 Embedding 配置名称（默认使用当前激活配置）")
    p_extract.add_argument("--clear-collection", action="store_true", help="启动时先清空目标 collection（默认不清空）")
    p_extract.add_argument("--quiet", action="store_true", help="不打印每轮详细进度")
    p_extract.add_argument("--chroma-space", default=None, choices=["l2", "cosine", "ip"], help="覆盖 Chroma hnsw:space")
    p_extract.add_argument("--import-excel", type=Path, default=None, help="可选：先导入该 Excel/CSV 到向量库（默认使用 --excel）")
    p_extract.add_argument("--import-sheet", default=None, help="导入文件 sheet（默认使用 --sheet）")
    p_extract.add_argument("--import-collection", default=None, help="导入目标 collection（默认与 --target-kb 一致）")
    p_extract.add_argument("--import-replace", action="store_true", help="导入前清空并重建目标 collection")
    p_extract.add_argument("--import-batch-size", type=int, default=128, help="导入批大小（默认 128）")
    p_extract.add_argument("--import-export-ids", default="", help="可选：导入后把导入 id 导出到文件（.csv/.xlsx/.json）")
    p_extract.add_argument("--import-only", action="store_true", help="仅执行导入，不运行萃取")
    p_extract.add_argument("--write-batch-size", type=int, default=None, help="覆盖全流程向量写入批大小")
    p_extract.add_argument("--query-batch-size", type=int, default=None, help="覆盖全流程向量查询批大小")
    p_extract.add_argument("--delete-batch-size", type=int, default=None, help="覆盖全流程向量删除批大小")
    p_extract.set_defaults(func=_run_extract)

    p_import = sub.add_parser(
        "import",
        help="导入 Excel/CSV QA 到 collection（Create）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main import --input .\\qa.xlsx --collection qa_excel_kb --replace\n"
            "  python -m rag_extract_split.main import --input .\\qa.xlsx --export-ids .\\out\\ids.xlsx\n"
            "  python -m rag_extract_split.main list --with-count"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_import.add_argument("--input", required=True, type=Path)
    p_import.add_argument("--sheet", default=None)
    p_import.add_argument("--collection", default="qa_excel_kb")
    p_import.add_argument("--replace", action="store_true")
    p_import.add_argument("--batch-size", type=int, default=128)
    p_import.add_argument("--embedding-config-name", default=None, help="可选：使用已保存的 Embedding 配置名称（默认使用当前激活配置）")
    p_import.add_argument("--export-ids", default="", help="可选：导入后把导入 id 导出到文件（.csv/.xlsx/.json）")
    p_import.set_defaults(func=_run_import)

    p_query = sub.add_parser(
        "query",
        help="批量检索（Read）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main query --input .\\qa_eval.xlsx --collection qa_excel_kb --top-k 5\n"
            "  python -m rag_extract_split.main get --collection qa_excel_kb --ids \"id1,id2\""
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_query.add_argument("--input", type=Path, default=None, help="输入 Excel/CSV 文件路径（与 --input-single 二选一）")
    p_query.add_argument("--input-single", dest="input_single", default="", help="单条 query 文本，直接测试")
    p_query.add_argument("--sheet", default=None)
    p_query.add_argument("--collection", default="qa_excel_kb")
    p_query.add_argument("--top-k", type=int, default=5)
    p_query.add_argument("--batch-size", type=int, default=128, help="查询批大小（默认 128）")
    p_query.add_argument("--output", default="")
    p_query.set_defaults(func=_run_query)

    p_get = sub.add_parser(
        "get",
        help="按 id 获取记录（Read）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main get --collection qa_excel_kb --ids \"id1,id2\"\n"
            "  python -m rag_extract_split.main query --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_get.add_argument("--collection", default="qa_excel_kb")
    p_get.add_argument("--ids", required=True, help="逗号分隔 id")
    p_get.add_argument("--batch-size", type=int, default=1000, help="读取批大小（默认 1000）")
    p_get.set_defaults(func=_run_get)

    p_list = sub.add_parser(
        "list",
        help="列出 collections（Read）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main list --with-count\n"
            "  python -m rag_extract_split.main import --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_list.add_argument("--with-count", action="store_true", help="同时输出每个 collection 文档数量")
    p_list.set_defaults(func=_run_list)

    p_update = sub.add_parser(
        "update",
        help="按 id 批量更新（Update）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main update --input .\\qa_update.xlsx --collection qa_excel_kb\n"
            "  python -m rag_extract_split.main get --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_update.add_argument("--input", required=True, type=Path)
    p_update.add_argument("--sheet", default=None)
    p_update.add_argument("--collection", default="qa_excel_kb")
    p_update.add_argument("--batch-size", type=int, default=128, help="更新写入批大小（默认 128）")
    p_update.add_argument("--embedding-config-name", default=None, help="可选：使用已保存的 Embedding 配置名称（默认使用当前激活配置）")
    p_update.set_defaults(func=_run_update)

    p_delete = sub.add_parser(
        "delete",
        help="按 id 删除或删除整个 collection（Delete）",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main delete --collection qa_excel_kb --ids \"id1,id2\"\n"
            "  python -m rag_extract_split.main delete --collection qa_excel_kb --input .\\ids.xlsx --id-column id\n"
            "  python -m rag_extract_split.main delete --collection qa_excel_kb          # 交互确认后删除整个 collection\n"
            "  python -m rag_extract_split.main delete --collection qa_excel_kb -y       # 跳过确认直接删除整个 collection\n"
            "  python -m rag_extract_split.main list --with-count"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_delete.add_argument("--collection", default="qa_excel_kb")
    p_delete.add_argument("--ids", default="", help="逗号分隔 id")
    p_delete.add_argument("--input", type=Path, default=None, help="可选：从 Excel/CSV 批量读取待删 id")
    p_delete.add_argument("--sheet", default=None, help="input 为 Excel 时的 sheet 名或索引")
    p_delete.add_argument("--id-column", default="id", help="input 文件中的 id 列名（默认 id）")
    p_delete.add_argument("--batch-size", type=int, default=1000, help="删除批大小（默认 1000）")
    p_delete.add_argument("-y", "--yes", action="store_true", help="删除整个 collection 时跳过确认")
    p_delete.set_defaults(func=_run_delete)

    p_single = sub.add_parser(
        "single",
        help="single 模式：LLM 相似问泛化 + embedding 相似度筛选，导出 CSV",
        epilog=(
            "相关命令:\n"
            "  python -m rag_extract_split.main single --single-input .\\qa.xlsx --single-sheet Sheet1 --single-output .\\out.csv\n"
            "  python -m rag_extract_split.main single --help"
        ),
        formatter_class=argparse.RawTextHelpFormatter,
    )
    p_single.add_argument("--single-input", required=True, type=Path, help="输入 CSV/Excel（query/answer 两列）")
    p_single.add_argument("--single-output", required=True, type=Path, help="输出 CSV 路径")
    p_single.add_argument("--single-sheet", default=None, help="Excel sheet 名或索引")
    p_single.add_argument("--single-q-column", default="query", help="问题列名（默认 query）")
    p_single.add_argument("--single-a-column", default="answer", help="答案列名（默认 answer）")
    p_single.add_argument("--single-k", type=int, default=0, help="每轮 LLM 生成候选数 k（默认 max(5, 2m)）")
    p_single.add_argument("--single-m", type=int, default=2, help="小根堆目标收集数量 m")
    p_single.add_argument("--single-threshold", type=float, default=0.80, help="相似度阈值 t")
    p_single.add_argument("--single-max-attempts", type=int, default=5, help="最大轮次 MaxStep")
    p_single.add_argument("--single-llm-trace-file", default="", help="可选：覆盖 LLM trace 日志文件名")
    p_single.add_argument("--llm-config", type=Path, default=None, help="可选：LLM 配置文件（JSON/YAML），覆盖 rag_llm 配置")
    p_single.add_argument("--llm-config-name", default=None, help="可选：使用已保存的 LLM 配置名称（默认使用 default）")
    p_single.add_argument("--embedding-config-name", default=None, help="可选：使用已保存的 Embedding 配置名称（默认使用当前激活配置）")
    p_single.set_defaults(func=_run_single)
    return parser


@log_cli_execution
def main() -> int:
    parser = _build_parser()
    argv = sys.argv[1:]
    if not argv:
        argv = ["extract"]
    elif argv[0].startswith("-") and argv[0] not in ("-h", "--help"):
        argv = ["extract"] + argv
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())

