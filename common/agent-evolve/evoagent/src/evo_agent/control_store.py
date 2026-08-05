"""Durable control-plane metadata for optimization submissions."""

from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Any

REQUEST_HASH_VERSION = "v1"


class SubmissionStatus(StrEnum):
    RECEIVED = "RECEIVED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    LOST = "LOST"


class SubmissionConflictError(Exception):
    """An idempotency key was reused with a different request."""


@dataclass(frozen=True)
class SubmissionReceipt:
    client_task_id: str
    request_hash_version: str
    request_hash: str
    job_id: str
    status: SubmissionStatus
    cancellation_requested: bool
    created_at: str
    updated_at: str


def canonical_request_hash(request_data: dict[str, Any]) -> str:
    """Hash canonical JSON v1 after removing the idempotency key."""
    payload = dict(request_data)
    payload.pop("client_task_id", None)
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class SubmissionControlStore:
    """SQLite source of truth for idempotent optimization submission metadata."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=30.0)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS optimization_submissions (
                    client_task_id TEXT PRIMARY KEY,
                    request_hash_version TEXT NOT NULL,
                    request_hash TEXT NOT NULL,
                    job_id TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL,
                    cancellation_requested INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )

    def is_available(self) -> bool:
        try:
            with self._connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                connection.execute(
                    "UPDATE optimization_submissions SET updated_at = updated_at WHERE 0"
                )
                connection.rollback()
        except sqlite3.Error:
            return False
        return True

    def create_or_get(
        self,
        *,
        client_task_id: str,
        request_data: dict[str, Any],
        job_id: str,
    ) -> tuple[SubmissionReceipt, bool]:
        request_hash = canonical_request_hash(request_data)
        now = datetime.now(UTC).isoformat()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM optimization_submissions WHERE client_task_id = ?",
                (client_task_id,),
            ).fetchone()
            if row is not None:
                receipt = self._from_row(row)
                if (
                    receipt.request_hash_version != REQUEST_HASH_VERSION
                    or receipt.request_hash != request_hash
                ):
                    raise SubmissionConflictError(client_task_id)
                return receipt, False
            connection.execute(
                """
                INSERT INTO optimization_submissions (
                    client_task_id, request_hash_version, request_hash, job_id,
                    status, cancellation_requested, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                """,
                (
                    client_task_id,
                    REQUEST_HASH_VERSION,
                    request_hash,
                    job_id,
                    SubmissionStatus.RECEIVED,
                    now,
                    now,
                ),
            )
            row = connection.execute(
                "SELECT * FROM optimization_submissions WHERE client_task_id = ?",
                (client_task_id,),
            ).fetchone()
            assert row is not None
            return self._from_row(row), True

    def get(self, client_task_id: str) -> SubmissionReceipt | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM optimization_submissions WHERE client_task_id = ?",
                (client_task_id,),
            ).fetchone()
        return self._from_row(row) if row is not None else None

    def mark_unfinished_lost(self) -> int:
        """Atomically mark submissions this process cannot resume as LOST."""
        now = datetime.now(UTC).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE optimization_submissions
                SET status = ?, updated_at = ?
                WHERE status IN (?, ?)
                """,
                (
                    SubmissionStatus.LOST,
                    now,
                    SubmissionStatus.RECEIVED,
                    SubmissionStatus.RUNNING,
                ),
            )
        return cursor.rowcount

    def update_status(self, job_id: str, status: SubmissionStatus) -> None:
        now = datetime.now(UTC).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE optimization_submissions
                SET status = ?, updated_at = ?
                WHERE job_id = ?
                """,
                (status, now, job_id),
            )

    def request_cancellation(self, job_id: str) -> None:
        now = datetime.now(UTC).isoformat()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE optimization_submissions
                SET cancellation_requested = 1, updated_at = ?
                WHERE job_id = ?
                """,
                (now, job_id),
            )

    @staticmethod
    def _from_row(row: sqlite3.Row) -> SubmissionReceipt:
        return SubmissionReceipt(
            client_task_id=str(row["client_task_id"]),
            request_hash_version=str(row["request_hash_version"]),
            request_hash=str(row["request_hash"]),
            job_id=str(row["job_id"]),
            status=SubmissionStatus(str(row["status"])),
            cancellation_requested=bool(row["cancellation_requested"]),
            created_at=str(row["created_at"]),
            updated_at=str(row["updated_at"]),
        )


__all__ = [
    "REQUEST_HASH_VERSION",
    "SubmissionConflictError",
    "SubmissionControlStore",
    "SubmissionReceipt",
    "SubmissionStatus",
    "canonical_request_hash",
]
