#!/usr/bin/env python3
"""Minimal fake Agent Runtime A2A entry for Gateway L2 smoke.

Listens POST /a2a and returns a tiny JSON-RPC success body.
"""
from __future__ import annotations

import argparse
import json
import logging
from typing import Callable
from wsgiref.simple_server import WSGIRequestHandler, make_server

LOG = logging.getLogger("fake_runtime")


class QuietHandler(WSGIRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return


def application(environ, start_response: Callable):
    method = environ.get("REQUEST_METHOD", "GET")
    path = environ.get("PATH_INFO", "")
    if method != "POST" or path not in ("/a2a", "/a2a/"):
        start_response("404 Not Found", [("Content-Type", "text/plain")])
        return [b"not found"]
    length = int(environ.get("CONTENT_LENGTH") or "0")
    if length > 0:
        environ["wsgi.input"].read(length)
    body = json.dumps(
        {
            "jsonrpc": "2.0",
            "id": "smoke-l2",
            "result": {"ok": True, "source": "fake-runtime"},
        }
    ).encode()
    start_response(
        "200 OK",
        [("Content-Type", "application/json"), ("Content-Length", str(len(body)))],
    )
    return [body]


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18094)
    args = ap.parse_args()
    httpd = make_server("127.0.0.1", args.port, application, handler_class=QuietHandler)
    LOG.info("fake_runtime listening on http://127.0.0.1:%s", args.port)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
