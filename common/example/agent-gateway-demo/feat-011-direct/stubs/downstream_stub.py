#!/usr/bin/env python3
"""Minimal Fake RDC + Fake Runtime for FEAT-011 Gateway examples.

Stands in for:
  - RDC (国庆侧选路服务) on --rdc-port (default 18092)
  - Agent Runtime A2A entry (下游 runtime) on --runtime-port (default 18094)

Not a product mock of FEAT-016 — only the two HTTP shapes Gateway calls.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

_LOGGER = logging.getLogger("downstream_stub")
_LOGGER.setLevel(logging.INFO)
_LOGGER.propagate = False
_LOG_HANDLER = logging.StreamHandler(sys.stdout)
_LOG_HANDLER.setFormatter(logging.Formatter("%(message)s"))
_LOGGER.addHandler(_LOG_HANDLER)


class RdcHandler(BaseHTTPRequestHandler):
    runtime_base = "http://127.0.0.1:18094"
    known_agents = {"scripted-verify", "travel-hotel", "default-agent-1"}

    def log_message(self, fmt, *args):  # quieter
        pass

    def do_GET(self):  # pylint: disable=huawei-invalid-name
        path = urlparse(self.path).path
        # GET /api/registry/instances/{tenant}/{agentId}
        prefix = "/api/registry/instances/"
        if not path.startswith(prefix):
            self.send_error(404)
            return
        parts = path[len(prefix):].split("/")
        if len(parts) != 2:
            self.send_error(400)
            return
        agent_id = parts[1]
        if agent_id not in self.known_agents:
            body = b"[]"
        else:
            body = json.dumps(
                [{"routeHandle": f"v2:stub-{agent_id}", "targetServiceId": f"svc-{agent_id}"}]
            ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # pylint: disable=huawei-invalid-name
        path = urlparse(self.path).path
        if path != "/api/registry/route-handle/resolve":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return
        handle = req.get("routeHandle", "")
        if not handle or not str(handle).startswith("v2:stub-"):
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps({"endpointUrl": self.runtime_base}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class RuntimeHandler(BaseHTTPRequestHandler):
    create_count = 0
    lock = threading.Lock()

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):  # pylint: disable=huawei-invalid-name
        path = urlparse(self.path).path
        if path not in ("/a2a", "a2a"):
            # accept "/a2a" only
            if not path.endswith("/a2a"):
                self.send_error(404)
                return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            req = {}
        method = req.get("method", "SendMessage")
        params = req.get("params") or {}
        message = params.get("message") or {}
        task_id = message.get("taskId")
        with self.lock:
            RuntimeHandler.create_count += 1
            n = RuntimeHandler.create_count
        if task_id:
            # resume
            body = {
                "jsonrpc": "2.0",
                "id": req.get("id", "1"),
                "result": {"id": task_id, "status": {"state": "TASK_STATE_WORKING"}, "kind": "resume-ack"},
            }
        else:
            tid = f"task-stub-{n}"
            body = {
                "jsonrpc": "2.0",
                "id": req.get("id", "1"),
                "result": {"id": tid, "status": {"state": "TASK_STATE_COMPLETED"}},
            }
        payload = json.dumps(body).encode()
        accept = self.headers.get("Accept", "")
        if "text/event-stream" in accept or method == "SendStreamingMessage":
            # minimal SSE: one data frame
            frame = f"data: {json.dumps(body)}\n\n".encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(frame)))
            self.end_headers()
            self.wfile.write(frame)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rdc-port", type=int, default=18092)
    ap.add_argument("--runtime-port", type=int, default=18094)
    args = ap.parse_args()
    RdcHandler.runtime_base = f"http://127.0.0.1:{args.runtime_port}"
    rdc = ThreadingHTTPServer(("127.0.0.1", args.rdc_port), RdcHandler)
    rt = ThreadingHTTPServer(("127.0.0.1", args.runtime_port), RuntimeHandler)
    threading.Thread(target=rdc.serve_forever, daemon=True).start()
    threading.Thread(target=rt.serve_forever, daemon=True).start()
    _LOGGER.info(
        "stub-rdc=http://127.0.0.1:%s stub-runtime=http://127.0.0.1:%s",
        args.rdc_port, args.runtime_port,
    )
    threading.Event().wait()


if __name__ == "__main__":
    main()
