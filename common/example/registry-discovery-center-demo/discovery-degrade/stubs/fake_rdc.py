#!/usr/bin/env python3
"""Fake RDC for Gateway L2 degrade wiring checks.

Gateway's HttpRdcRouteClient only needs:
  GET  /api/registry/instances/{tenant}/{agentId}
  POST /api/registry/route-handle/resolve

Modes:
  --mode ok          always 200
  --mode fail-after  first N successful responses, then 503 (default N=2)

Usage:
  ./stubs/fake_rdc.py --port 18092 --mode fail-after --ok-count 2
  # point Gateway: gateway.rdc.base-url=http://127.0.0.1:18092
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


class State:
    ok_remaining = 2
    mode = "ok"
    runtime_base = "http://127.0.0.1:18094"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _should_fail(self) -> bool:
        if State.mode != "fail-after":
            return False
        if State.ok_remaining <= 0:
            return True
        State.ok_remaining -= 1
        return False

    def _send(self, code: int, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        prefix = "/api/registry/instances/"
        if not path.startswith(prefix):
            self.send_error(404)
            return
        if self._should_fail():
            self._send(503, b'{"error":"REGISTRY_UNAVAILABLE"}')
            return
        parts = path[len(prefix) :].split("/")
        if len(parts) != 2:
            self.send_error(400)
            return
        agent = parts[1]
        body = json.dumps(
            [{"routeHandle": f"v2:stub-{agent}", "serviceId": f"svc-{agent}", "health": "ONLINE"}]
        ).encode()
        self._send(200, body)

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/api/registry/route-handle/resolve":
            self.send_error(404)
            return
        if self._should_fail():
            self._send(503, b'{"error":"REGISTRY_UNAVAILABLE"}')
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return
        handle = str(req.get("routeHandle", ""))
        if not handle.startswith("v2:stub-"):
            self._send(404, b'{"error":"ENTRY_NOT_FOUND"}')
            return
        body = json.dumps(
            {
                "instanceId": "stub-inst",
                "endpointUrl": State.runtime_base,
                "routeKey": "/v1/query",
                "contractVersion": "1.0.0",
                "capabilityVersion": "1.0.0",
            }
        ).encode()
        self._send(200, body)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18092)
    ap.add_argument("--mode", choices=("ok", "fail-after"), default="fail-after")
    ap.add_argument("--ok-count", type=int, default=2)
    ap.add_argument("--runtime-base", default="http://127.0.0.1:18094")
    args = ap.parse_args()
    State.mode = args.mode
    State.ok_remaining = args.ok_count
    State.runtime_base = args.runtime_base
    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(
        f"fake_rdc listening on http://127.0.0.1:{args.port} mode={args.mode} ok_count={args.ok_count}",
        flush=True,
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
