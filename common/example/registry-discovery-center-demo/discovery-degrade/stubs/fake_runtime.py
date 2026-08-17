#!/usr/bin/env python3
"""Minimal fake Agent Runtime A2A entry for Gateway L2 smoke.

Listens POST /a2a and returns a tiny JSON-RPC success body.
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path not in ("/a2a", "/a2a/"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        _ = self.rfile.read(length) if length else b""
        body = json.dumps(
            {
                "jsonrpc": "2.0",
                "id": "smoke-l2",
                "result": {"ok": True, "source": "fake-runtime"},
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18094)
    args = ap.parse_args()
    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fake_runtime listening on http://127.0.0.1:{args.port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
