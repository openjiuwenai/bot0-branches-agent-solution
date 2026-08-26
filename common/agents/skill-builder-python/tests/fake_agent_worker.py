"""Protocol-compatible worker used by subprocess adapter tests."""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

from skill_builder.agent_worker import PROTOCOL_VERSION


def main() -> int:
    request_path, result_path, events_path = map(Path, sys.argv[1:4])
    request = json.loads(request_path.read_text(encoding="utf-8"))
    delay = float(os.getenv("FAKE_AGENT_WORKER_DELAY") or 0)
    if delay:
        time.sleep(delay)
    events_path.write_text(
        json.dumps(
            {
                "schema_version": PROTOCOL_VERSION,
                "sequence": 1,
                "event_type": "agent.fake",
                "summary": "fake event",
                "payload": {"phase": request.get("run_phase")},
            }
        )
        + "\n",
        encoding="utf-8",
    )
    if os.getenv("FAKE_AGENT_WORKER_ERROR"):
        result = {
            "schema_version": PROTOCOL_VERSION,
            "ok": False,
            "error_type": "agent_lifecycle",
            "error_code": "fake_rejected",
            "phase": request.get("run_phase"),
            "error": "fake lifecycle rejection",
        }
        result_path.write_text(json.dumps(result), encoding="utf-8")
        return 11
    result = {
        "schema_version": PROTOCOL_VERSION,
        "ok": True,
        "result": {
            "raw_output_text": request.get("materials_markdown"),
            "session_id": "fake-session",
            "files_read": ["inputs/material.md"],
            "files_listed": [],
            "files_written": ["generated-skill/SKILL.md"],
            "final_response": {"status": "ready"},
            "submission_status": {"ok": True},
        },
    }
    result_path.write_text(json.dumps(result), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
