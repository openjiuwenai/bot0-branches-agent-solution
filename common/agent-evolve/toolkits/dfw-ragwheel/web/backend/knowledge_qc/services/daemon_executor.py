from __future__ import annotations

import queue
import threading
from concurrent.futures import Future
from typing import Any, Callable, Optional


class DaemonThreadPoolExecutor:
    """轻量 daemon 线程池（兼容 Python 3.8+，不依赖 concurrent.futures 私有 API）。"""

    def __init__(self, max_workers: int, thread_name_prefix: str = "kqc-worker"):
        if max_workers < 1:
            raise ValueError("max_workers must be >= 1")
        self._max_workers = max_workers
        self._thread_name_prefix = thread_name_prefix
        self._work_queue: queue.Queue[
            Optional[tuple[Future, Callable[..., Any], tuple, dict]]
        ] = queue.Queue()
        self._threads: list[threading.Thread] = []
        self._shutdown = False
        self._shutdown_lock = threading.Lock()

        for index in range(max_workers):
            thread = threading.Thread(
                target=self._worker,
                name=f"{thread_name_prefix}_{index}",
                daemon=True,
            )
            thread.start()
            self._threads.append(thread)

    def _worker(self) -> None:
        while True:
            item = self._work_queue.get()
            try:
                if item is None:
                    return
                future, fn, args, kwargs = item
                if future.cancelled():
                    continue
                if not future.set_running_or_notify_cancel():
                    continue
                try:
                    result = fn(*args, **kwargs)
                except BaseException as exc:
                    future.set_exception(exc)
                else:
                    future.set_result(result)
            finally:
                self._work_queue.task_done()

    def submit(self, fn: Callable[..., Any], /, *args, **kwargs) -> Future:
        with self._shutdown_lock:
            if self._shutdown:
                raise RuntimeError("cannot schedule new futures after shutdown")
        future: Future = Future()
        self._work_queue.put((future, fn, args, kwargs))
        return future

    def shutdown(self, wait: bool = True) -> None:
        with self._shutdown_lock:
            if self._shutdown:
                return
            self._shutdown = True
        for _ in self._threads:
            self._work_queue.put(None)
        if wait:
            for thread in self._threads:
                thread.join()

    def __enter__(self) -> DaemonThreadPoolExecutor:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.shutdown(wait=True)
