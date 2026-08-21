from __future__ import annotations

from concurrent.futures import TimeoutError as FuturesTimeout

import chromadb
from chromadb.config import Settings

from backend.knowledge_qc.services.daemon_executor import DaemonThreadPoolExecutor

# Chroma 0.5.x 默认向 app.posthog.com 上报遥测，离线或受限网络下会刷屏并拖慢初始化
_CHROMA_SETTINGS = Settings(anonymized_telemetry=False)


def open_chroma_client(persist_dir: str, init_timeout: float):
    """避免 GUI 占用库时 CLI 无限阻塞。"""
    with DaemonThreadPoolExecutor(max_workers=1, thread_name_prefix="chroma-open") as pool:
        future = pool.submit(
            chromadb.PersistentClient, path=persist_dir, settings=_CHROMA_SETTINGS
        )
        try:
            return future.result(timeout=init_timeout)
        except FuturesTimeout:
            raise RuntimeError(
                f"连接 Chroma 超时（{init_timeout}s）：{persist_dir}\n"
                "请关闭图形界面或其它占用该目录的进程后重试。"
            ) from None
