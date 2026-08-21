from __future__ import annotations

from typing import Callable, TypeVar

from backend.knowledge_qc.services.llm import LLMRequestError

T = TypeVar("T")


def call_llm(fn: Callable[[], T]) -> T:
    try:
        return fn()
    except LLMRequestError:
        raise
    except Exception as e:
        raise LLMRequestError(str(e)) from e
