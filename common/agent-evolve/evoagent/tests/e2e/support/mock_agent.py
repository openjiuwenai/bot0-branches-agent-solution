"""Small business Agent process that only reloads AgentRule.md on restart."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

RULE_PATH = Path(os.environ["EVO_E2E_RULE_PATH"])
LOG_PATH = Path(os.environ["EVO_E2E_AGENT_LOG"])
AGENT_ID = "e2e-agent"

app = FastAPI()
_ready = True
_answer = ""


def _load_answer() -> str:
    content = RULE_PATH.read_text(encoding="utf-8")
    match = re.search(r"^ANSWER=(\S+)\s*$", content, flags=re.MULTILINE)
    if match is None:
        raise RuntimeError("AgentRule.md is missing ANSWER=<value>")
    return match.group(1)


def _line(
    *,
    timestamp: str,
    trace_id: str,
    conversation_id: str,
    tag: str,
    message: dict[str, Any],
) -> str:
    return "\x01".join(
        [
            timestamp,
            "INFO",
            "e2e_mock_agent:1",
            trace_id,
            AGENT_ID,
            conversation_id,
            tag,
            "0",
            json.dumps(message, ensure_ascii=False),
        ]
    )


def _write_trace(conversation_id: str, query: str, answer: str) -> None:
    timestamp = datetime.now(UTC).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    trace_id = f"trace-{uuid.uuid4().hex}"
    call_id = f"call-{uuid.uuid4().hex}"
    lines = [
        _line(
            timestamp=timestamp,
            trace_id=trace_id,
            conversation_id=conversation_id,
            tag="TAG_HTTP_REQUEST_START",
            message={"id": trace_id, "timestamp": timestamp},
        ),
        _line(
            timestamp=timestamp,
            trace_id=trace_id,
            conversation_id=conversation_id,
            tag="TAG_LLM_CALL_START",
            message={
                "id": call_id,
                "type": "GENERATION",
                "model": "e2e-rule-agent",
                "input": {"messages": [{"role": "user", "content": query}]},
            },
        ),
        _line(
            timestamp=timestamp,
            trace_id=trace_id,
            conversation_id=conversation_id,
            tag="TAG_LLM_CALL_END",
            message={
                "id": call_id,
                "end_time": timestamp,
                "output": {"role": "assistant", "content": answer},
                "total_cost": 0,
            },
        ),
        _line(
            timestamp=timestamp,
            trace_id=trace_id,
            conversation_id=conversation_id,
            tag="TAG_HTTP_REQUEST_END",
            message={"id": trace_id, "output": {"status_code": 200}},
        ),
    ]
    with LOG_PATH.open("a", encoding="utf-8") as stream:
        stream.write("\n".join(lines) + "\n")


@app.on_event("startup")
async def _startup() -> None:
    global _answer
    _answer = _load_answer()
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG_PATH.touch(exist_ok=True)


@app.get("/health")
async def health() -> JSONResponse:
    if not _ready:
        return JSONResponse({"status": "restarting"}, status_code=503)
    return JSONResponse({"status": "ok", "answer": _answer})


@app.post("/__test__/restart")
async def restart() -> dict[str, str]:
    global _ready
    if not _ready:
        raise HTTPException(status_code=409, detail="restart already in progress")
    _ready = False

    async def _reload() -> None:
        global _answer, _ready
        await asyncio.sleep(0.35)
        _answer = _load_answer()
        _ready = True

    asyncio.create_task(_reload())
    return {"status": "restarting"}


@app.post("/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}")
async def converse(
    project_id: str,
    agent_id: str,
    conversation_id: str,
    body: dict[str, Any],
) -> StreamingResponse:
    del project_id, agent_id
    if not _ready:
        raise HTTPException(status_code=503, detail="agent is restarting")
    query = str(body.get("input", {}).get("query", ""))
    answer = _answer
    _write_trace(conversation_id, query, answer)

    async def _events() -> Any:
        event = {"custom_rsp_data": {"event": "final_answer_chunk", "content": answer}}
        yield f"data: {json.dumps(event)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(_events(), media_type="text/event-stream")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
