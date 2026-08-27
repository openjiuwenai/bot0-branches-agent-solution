from __future__ import annotations

from backend.knowledge_qc.services.daemon_executor import DaemonThreadPoolExecutor


def qc_worker_count(settings: dict) -> int:
    """相似问/意图描述逐行质检并发数（rules.yaml → qc.worker_count）。"""
    rules = settings.get("rules", {})
    return max(1, int(rules.get("qc", {}).get("worker_count", 1)))


def create_qc_thread_pool(worker_count: int) -> DaemonThreadPoolExecutor:
    return DaemonThreadPoolExecutor(
        max_workers=worker_count,
        thread_name_prefix="kqc-worker",
    )
