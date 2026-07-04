#!/usr/bin/env python3
"""Minimal do-nothing HTTP sink for the upload-overhead cell (plan 034).

Accepts any request, reads and discards the body, replies 202 Accepted — so the plugin's
synchronous upload path runs end-to-end without a real BuildHound server. No token, no storage.
"""
import http.server
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    def _drain_and_accept(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length:
            self.rfile.read(length)
        self.send_response(202)
        self.end_headers()

    def do_POST(self):  # noqa: N802 (http.server API)
        self._drain_and_accept()

    def do_GET(self):  # noqa: N802
        self._drain_and_accept()

    def log_message(self, *_args):  # silence per-request logging
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
