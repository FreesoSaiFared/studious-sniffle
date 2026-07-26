#!/usr/bin/env python3
"""Cross-platform black-box smoke test for the compiled aa_proxy binary."""

from __future__ import annotations

import argparse
import json
import socket
import sqlite3
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        payload = json.dumps({"ok": True, "path": self.path}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def log_message(self, _format: str, *args: object) -> None:
        del args


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def connect_when_ready(port: int, timeout: float = 15.0) -> socket.socket:
    """Return the first successful connection, without consuming a probe connection."""
    deadline = time.monotonic() + timeout
    last_error: OSError | None = None
    while time.monotonic() < deadline:
        try:
            return socket.create_connection(("127.0.0.1", port), timeout=1)
        except OSError as error:
            last_error = error
            time.sleep(0.05)
    raise TimeoutError(f"port {port} did not become ready: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("proxy_binary")
    parser.add_argument("--workdir", default="proxy-smoke-output")
    args = parser.parse_args()

    workdir = Path(args.workdir).resolve()
    workdir.mkdir(parents=True, exist_ok=True)
    db_path = workdir / "proxy-smoke.db"
    if db_path.exists():
        db_path.unlink()

    server_port = free_port()
    proxy_port = free_port()
    server = ThreadingHTTPServer(("127.0.0.1", server_port), Handler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    proxy_command = [
        str(Path(args.proxy_binary).resolve()),
        "--db",
        str(db_path),
        "--session",
        "proxy-smoke",
        "--listen",
        f"127.0.0.1:{proxy_port}",
        "--max-connections",
        "1",
    ]
    proxy = subprocess.Popen(
        proxy_command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    try:
        request = (
            f"GET http://127.0.0.1:{server_port}/through-proxy?probe=1 HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{server_port}\r\n"
            "Connection: close\r\n\r\n"
        ).encode("ascii")
        with connect_when_ready(proxy_port) as client:
            client.settimeout(10)
            client.sendall(request)
            chunks: list[bytes] = []
            while True:
                chunk = client.recv(65536)
                if not chunk:
                    break
                chunks.append(chunk)
        response = b"".join(chunks)
        if b"200 OK" not in response or b'"ok": true' not in response:
            raise AssertionError(f"unexpected proxied response: {response[:500]!r}")

        stdout, stderr = proxy.communicate(timeout=20)
        if proxy.returncode != 0:
            raise AssertionError(
                f"proxy exited {proxy.returncode}\nstdout:\n{stdout}\nstderr:\n{stderr}"
            )

        with sqlite3.connect(db_path) as connection:
            row = connection.execute(
                "SELECT method,url,status_code,source,response_body "
                "FROM requests WHERE session_id='proxy-smoke' ORDER BY sequence LIMIT 1"
            ).fetchone()
        if row is None:
            raise AssertionError("proxy did not persist a request")
        method, url, status_code, source, response_body = row
        assert method == "GET", row
        assert url.endswith("/through-proxy?probe=1"), row
        assert status_code == 200, row
        assert source == "proxy", row
        assert response_body and '"ok": true' in response_body, row

        result = {
            "status": "PASS",
            "proxy_binary": str(Path(args.proxy_binary).resolve()),
            "database": str(db_path),
            "captured": {
                "method": method,
                "url": url,
                "status_code": status_code,
                "source": source,
            },
        }
        (workdir / "proxy-smoke-result.json").write_text(
            json.dumps(result, indent=2), encoding="utf-8"
        )
        print(json.dumps(result, indent=2))
        return 0
    finally:
        server.shutdown()
        server.server_close()
        if proxy.poll() is None:
            proxy.kill()
            proxy.wait(timeout=5)


if __name__ == "__main__":
    sys.exit(main())
