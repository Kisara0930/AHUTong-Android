#!/usr/bin/env python3
"""Read-only plugin market HTTP API; publishing is an offline operator command."""
import argparse
import json
import re
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from manifest import MarketError, json_object
from store import Client, PackageStore
from host_updates import HostUpdateStore


MAX_BODY = 128 * 1024


class MarketServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store):
        self.store = store
        self.host_updates = HostUpdateStore(store)
        super().__init__(address, Handler)

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(20)
        return request, address


class Handler(BaseHTTPRequestHandler):
    server_version = "AHUTongPluginMarket/1"

    def do_GET(self):
        self.dispatch()

    def do_HEAD(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def json_response(self, status, data):
        raw = json.dumps(data, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def read_json(self):
        if self.headers.get("Transfer-Encoding"):
            raise MarketError("Chunked request bodies are not accepted")
        if self.headers.get_content_type() != "application/json":
            raise MarketError("Content-Type must be application/json", status=415)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise MarketError("Invalid Content-Length") from error
        if length < 1 or length > MAX_BODY:
            raise MarketError("Invalid request body size", status=413)
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise MarketError("Incomplete request body")
        return json_object(raw)

    def query_client(self, query):
        values = parse_qs(query, strict_parsing=True)
        def single(key):
            if key not in values or len(values[key]) != 1:
                raise MarketError("One %s query parameter is required" % key)
            return values[key][0]
        try:
            return Client({"hostApiVersion": int(single("hostApiVersion")),
                           "runtimeVersion": single("runtimeVersion"),
                           "sdk": int(single("sdk")), "abis": single("abis").split(",")})
        except ValueError as error:
            if isinstance(error, MarketError):
                raise
            raise MarketError("Invalid compatibility query") from error

    def dispatch(self):
        try:
            parsed = urlsplit(self.path)
            path = parsed.path.rstrip("/")
            if self.command in ("GET", "HEAD"):
                if path == "/healthz":
                    return self.json_response(200, {"status": "ok", "schemaVersion": 1})
                if path == "/v1/plugins":
                    return self.json_response(200, self.server.store.catalog(self.query_client(parsed.query)))
                if path == "/v1/host/update":
                    values = parse_qs(parsed.query, strict_parsing=True)
                    def one(name):
                        if name not in values or len(values[name]) != 1:
                            raise MarketError("One %s query parameter is required" % name)
                        return values[name][0]
                    return self.json_response(200, self.server.host_updates.update(
                        int(one("versionCode")), int(one("hostApiVersion")), one("runtimeVersion"),
                        int(one("sdk")) if "sdk" in values else None))
                host_apk = re.fullmatch(r"/v1/host/packages/([1-9][0-9]*)/([a-f0-9]{64})\.apk", path)
                if host_apk:
                    file, metadata = self.server.host_updates.package(int(host_apk[1]), host_apk[2])
                    return self.stream_file(file, metadata, "application/vnd.android.package-archive", "ahutong-host-%s.apk" % host_apk[1])
                detail = re.fullmatch(r"/v1/plugins/([a-z][a-z0-9_]{1,63})", path)
                if detail:
                    return self.json_response(200, self.server.store.detail(detail[1], self.query_client(parsed.query)))
                package = re.fullmatch(r"/v1/packages/([a-z][a-z0-9_]{1,63})/([1-9][0-9]*)/([a-f0-9]{64})\.ahup", path)
                if package:
                    return self.download(*package.groups())
            if self.command == "POST" and path in ("/v1/resolve", "/v1/updates"):
                return self.json_response(200, self.server.store.resolve(self.read_json(), path == "/v1/updates"))
            raise MarketError("Endpoint not found", "not_found", 404)
        except MarketError as error:
            self.json_response(error.status, {"error": {"code": error.code, "message": str(error)}})
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            self.close_connection = True
        except (ValueError, UnicodeError):
            self.json_response(400, {"error": {"code": "invalid_request", "message": "Malformed request"}})
        except Exception:
            # Never expose storage paths, certificates, or a traceback to clients.
            self.log_error("Internal market error")
            self.json_response(500, {"error": {"code": "internal_error", "message": "Market temporarily unavailable"}})

    def download(self, identifier, version, sha256):
        path, record = self.server.store.package(identifier, int(version), sha256)
        return self.stream_file(path, record, "application/vnd.ahutong.plugin", "%s-%s.ahup" % (identifier, version))

    def stream_file(self, path, record, content_type, filename):
        sha256 = record["sha256"]
        size = record["size"]
        etag = '"%s"' % sha256
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Cache-Control", "public, max-age=0, must-revalidate")
            self.end_headers()
            return
        start, end, status = 0, size - 1, 200
        requested_range = self.headers.get("Range")
        if requested_range and self.headers.get("If-Range", etag) == etag:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", requested_range)
            if not match or not (match[1] or match[2]):
                raise MarketError("Only one byte range is supported", "invalid_range", 416)
            if match[1]:
                start = int(match[1])
                end = min(int(match[2]), size - 1) if match[2] else size - 1
            else:
                suffix = int(match[2])
                start = max(0, size - suffix)
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%s" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        # Open before headers so missing files can still return a structured error.
        with path.open("rb") as stream:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            # Immutable URL, but revalidate to enforce emergency withdrawals.
            self.send_header("Cache-Control", "public, max-age=0, must-revalidate")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % filename)
            if status == 206:
                self.send_header("Content-Range", "bytes %s-%s/%s" % (start, end, size))
            self.end_headers()
            if self.command == "HEAD":
                return
            stream.seek(start)
            remaining = end - start + 1
            while remaining:
                chunk = stream.read(min(remaining, 1024 * 1024))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8092)
    parser.add_argument("--required", action="append", default=[], help="Required bootstrap plugin id; repeatable")
    args = parser.parse_args()
    server = MarketServer((args.bind, args.port), PackageStore(args.store, required=args.required))
    print("AHUTong plugin market listening on %s:%s" % server.server_address, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
