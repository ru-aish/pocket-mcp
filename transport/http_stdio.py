#!/usr/bin/env python3
"""Small, project-independent MCP HTTP-to-stdio transport. No device-specific code."""
from __future__ import annotations

import argparse
import http.client
import json
from pathlib import Path
import socket
import ssl
import sys
from urllib.parse import urlsplit

MAX_RESPONSE = 8 * 1024 * 1024
MAX_REQUEST = 1024 * 1024


class TransportFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class HttpMcp:
    def __init__(self, url: str, token: str, timeout: float = 20):
        target = urlsplit(url)
        if target.username or target.password or target.fragment or not target.hostname:
            raise ValueError("Use an endpoint URL without credentials or a fragment")
        if target.scheme != "https" and not (
            target.scheme == "http" and target.hostname in ("localhost", "127.0.0.1", "::1")
        ):
            raise ValueError("Use HTTPS; HTTP is allowed only for local development")
        if not token or any(c in token for c in "\r\n"):
            raise ValueError("A nonempty bearer token is required")
        self.target, self.token, self.timeout = target, token, timeout
        self.path = target.path or "/mcp"
        if target.query:
            self.path += "?" + target.query
        self.connection = None
        self.session_id = None
        self.protocol = "2025-11-25"

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def _connect(self):
        if self.connection is None:
            if self.target.scheme == "https":
                self.connection = http.client.HTTPSConnection(
                    self.target.hostname, self.target.port, timeout=self.timeout,
                    context=ssl.create_default_context()
                )
            else:
                self.connection = http.client.HTTPConnection(
                    self.target.hostname, self.target.port, timeout=self.timeout
                )

    @staticmethod
    def retry_safe(message):
        # App/tool observations may also launch or change apps. Keep this deliberately narrow.
        return message.get("method") in ("initialize", "ping", "tools/list", "resources/list", "prompts/list")

    def forward(self, message):
        attempts = 2 if self.retry_safe(message) else 1
        for attempt in range(attempts):
            try:
                return self._once(message)
            except (OSError, http.client.HTTPException, socket.timeout):
                self.close()
                if attempt + 1 == attempts:
                    raise TransportFailure("TRANSPORT_FAILED" if self.retry_safe(message) else "RESULT_UNKNOWN")
            except TransportFailure:
                self.close()
                raise
        raise AssertionError("unreachable")

    def _once(self, message):
        self._connect()
        headers = {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "ngrok-skip-browser-warning": "true",
        }
        if message.get("method") != "initialize":
            headers["MCP-Protocol-Version"] = self.protocol
        if self.session_id:
            headers["MCP-Session-Id"] = self.session_id
        data = json.dumps(message, separators=(",", ":")).encode()
        if len(data) > MAX_REQUEST:
            raise TransportFailure("REQUEST_TOO_LARGE")
        self.connection.request("POST", self.path, body=data, headers=headers)
        response = self.connection.getresponse()
        session = response.getheader("MCP-Session-Id")
        if response.status == 202:
            if len(response.read(MAX_RESPONSE + 1)) > MAX_RESPONSE:
                raise TransportFailure("RESPONSE_TOO_LARGE")
            if "id" in message:
                raise TransportFailure("RESULT_UNKNOWN")
            return None
        if response.status != 200:
            self.close()
            raise TransportFailure("HTTP_" + str(response.status))
        content_type = response.getheader("Content-Type", "").split(";", 1)[0].strip()
        if content_type == "text/event-stream":
            parsed = self._sse(response, message.get("id"))
            # We stop after one matching response; discard the connection rather than leave unread bytes.
            self.close()
        elif content_type == "application/json":
            body = response.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                self.close()
                raise TransportFailure("RESPONSE_TOO_LARGE")
            try:
                parsed = json.loads(body)
            except ValueError:
                self.close()
                raise TransportFailure("INVALID_JSON_RESPONSE")
        else:
            self.close()
            raise TransportFailure("UNEXPECTED_CONTENT_TYPE")
        if not isinstance(parsed, dict) or parsed.get("jsonrpc") != "2.0" or parsed.get("id") != message.get("id") or ("result" in parsed) == ("error" in parsed):
            self.close()
            raise TransportFailure("INVALID_RPC_RESPONSE")
        if message.get("method") == "initialize" and "result" in parsed:
            if not isinstance(parsed["result"], dict) or not isinstance(parsed["result"].get("protocolVersion"), str):
                raise TransportFailure("INVALID_INITIALIZE_RESPONSE")
            if session:
                self.session_id = session
            self.protocol = parsed["result"].get("protocolVersion", self.protocol)
        return parsed

    @staticmethod
    def _sse(response, wanted_id):
        count, lines = 0, []
        while True:
            line = response.readline(MAX_RESPONSE + 1)
            count += len(line)
            if count > MAX_RESPONSE:
                raise TransportFailure("RESPONSE_TOO_LARGE")
            if not line:
                raise TransportFailure("INCOMPLETE_EVENT_STREAM")
            try:
                text = line.decode("utf-8").rstrip("\r\n")
            except UnicodeError:
                raise TransportFailure("INVALID_EVENT_STREAM")
            if text.startswith("data:"):
                lines.append(text[5:].lstrip(" "))
            elif not text and lines:
                payload = "\n".join(lines)
                lines = []
                if not payload.strip():
                    continue
                try:
                    event = json.loads(payload)
                except ValueError:
                    raise TransportFailure("INVALID_EVENT_STREAM")
                if isinstance(event, dict) and isinstance(event.get("method"), str):
                    # This deliberately small relay does not implement bidirectional server requests.
                    raise TransportFailure("SERVER_INITIATED_MESSAGES_UNSUPPORTED")
                if isinstance(event, dict) and event.get("id") == wanted_id:
                    return event


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--connection", type=Path, required=True,
                        help="Private JSON file containing url and bearer_token")
    args = parser.parse_args()
    try:
        config = json.loads(args.connection.read_text())
        client = HttpMcp(config["url"], config["bearer_token"])
    except (OSError, ValueError, KeyError, TypeError):
        print("Connection configuration is missing or invalid", file=sys.stderr)
        return 2
    try:
        for line in sys.stdin:
            message = None
            try:
                if len(line.encode()) > MAX_REQUEST:
                    raise ValueError("size")
                message = json.loads(line)
                if not isinstance(message, dict) or message.get("jsonrpc") != "2.0" or not isinstance(message.get("method"), str):
                    raise ValueError("RPC envelope")
                result = client.forward(message)
            except TransportFailure as exc:
                if isinstance(message, dict) and "id" not in message:
                    print("Notification transport failed: " + exc.code, file=sys.stderr)
                    continue
                result = {"jsonrpc": "2.0", "id": message.get("id") if isinstance(message, dict) else None,
                          "error": {"code": -32098, "message": exc.code,
                                    "data": {"retry_mutating_action": False,
                                             "next_step": "Inspect operation/current state before repeating any action"}}}
            except ValueError:
                result = {"jsonrpc": "2.0", "id": None,
                          "error": {"code": -32700, "message": "Invalid JSON-RPC input"}}
            if result is not None:
                print(json.dumps(result, separators=(",", ":")), flush=True)
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
