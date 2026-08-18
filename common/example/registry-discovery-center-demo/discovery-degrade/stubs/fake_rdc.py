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
import logging
from typing import Callable
from wsgiref.simple_server import WSGIRequestHandler, make_server

LOG = logging.getLogger("fake_rdc")


class State:
    ok_remaining = 2
    mode = "ok"
    runtime_base = "http://127.0.0.1:18094"


class QuietHandler(WSGIRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return


def should_fail() -> bool:
    if State.mode != "fail-after":
        return False
    if State.ok_remaining <= 0:
        return True
    State.ok_remaining -= 1
    return False


def read_body(environ) -> bytes:
    length = int(environ.get("CONTENT_LENGTH") or "0")
    if length <= 0:
        return b"{}"
    return environ["wsgi.input"].read(length)


def json_response(status: str, payload: dict | list) -> tuple[str, list[tuple[str, str]], list[bytes]]:
    body = json.dumps(payload).encode()
    headers = [("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
    return status, headers, [body]


def handle_instances(path: str) -> tuple[str, list[tuple[str, str]], list[bytes]]:
    prefix = "/api/registry/instances/"
    if not path.startswith(prefix):
        return "404 Not Found", [("Content-Type", "text/plain")], [b"not found"]
    if should_fail():
        return json_response("503 Service Unavailable", {"error": "REGISTRY_UNAVAILABLE"})
    parts = path[len(prefix):].split("/")
    if len(parts) != 2:
        return "400 Bad Request", [("Content-Type", "text/plain")], [b"bad request"]
    agent = parts[1]
    return json_response(
        "200 OK",
        [{"routeHandle": f"v2:stub-{agent}", "serviceId": f"svc-{agent}", "health": "ONLINE"}],
    )


def handle_resolve(environ) -> tuple[str, list[tuple[str, str]], list[bytes]]:
    if should_fail():
        return json_response("503 Service Unavailable", {"error": "REGISTRY_UNAVAILABLE"})
    try:
        req = json.loads(read_body(environ).decode() or "{}")
    except json.JSONDecodeError:
        return "400 Bad Request", [("Content-Type", "text/plain")], [b"bad request"]
    handle = str(req.get("routeHandle", ""))
    if not handle.startswith("v2:stub-"):
        return json_response("404 Not Found", {"error": "ENTRY_NOT_FOUND"})
    return json_response(
        "200 OK",
        {
            "instanceId": "stub-inst",
            "endpointUrl": State.runtime_base,
            "routeKey": "/v1/query",
            "contractVersion": "1.0.0",
            "capabilityVersion": "1.0.0",
        },
    )


def application(environ, start_response: Callable):
    method = environ.get("REQUEST_METHOD", "GET")
    path = environ.get("PATH_INFO", "")
    if method == "GET":
        status, headers, body = handle_instances(path)
    elif method == "POST" and path == "/api/registry/route-handle/resolve":
        status, headers, body = handle_resolve(environ)
    else:
        status, headers, body = "404 Not Found", [("Content-Type", "text/plain")], [b"not found"]
    start_response(status, headers)
    return body


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18092)
    ap.add_argument("--mode", choices=("ok", "fail-after"), default="fail-after")
    ap.add_argument("--ok-count", type=int, default=2)
    ap.add_argument("--runtime-base", default="http://127.0.0.1:18094")
    args = ap.parse_args()
    State.mode = args.mode
    State.ok_remaining = args.ok_count
    State.runtime_base = args.runtime_base
    httpd = make_server("127.0.0.1", args.port, application, handler_class=QuietHandler)
    LOG.info(
        "fake_rdc listening on http://127.0.0.1:%s mode=%s ok_count=%s",
        args.port,
        args.mode,
        args.ok_count,
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
