"""为已启动的测试保留原监听地址；透明转发到同一owned沙箱的新随机Docker端口。"""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit
from run_suite import save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration", type=Path, required=True)
    parser.add_argument("--prior-binding", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    conf = json.loads(args.configuration.read_text(encoding="utf-8"))
    binding = json.loads(args.prior_binding.read_text(encoding="utf-8"))
    old, target = urlsplit(binding["prior_endpoint"]), urlsplit(conf["endpoint"])
    if conf["container_id"] != binding["container_id"] or old.scheme != "http" or old.hostname != "127.0.0.1" or target.hostname != "127.0.0.1":
        raise ValueError("owned_endpoint_identity_mismatch")
    observed = json.loads(subprocess.check_output(["docker", "inspect", "--format",
        '{"id":{{json .Id}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"ports":{{json (index .NetworkSettings.Ports "8200/tcp")}}}',
        conf["container_id"]], text=True))
    if (observed["id"] != conf["container_id"] or observed["project"] != conf["prefix"] or
            observed["ports"] != [{"HostIp": "127.0.0.1", "HostPort": str(target.port)}]):
        raise ValueError("owned_actual_port_mismatch")

    class Forward(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else None
            headers = {name: value for name, value in self.headers.items() if name.lower() not in {"host", "connection"}}
            connection = http.client.HTTPConnection("127.0.0.1", target.port, timeout=180)
            try:
                connection.request(self.command, self.path, body, headers)
                response = connection.getresponse()
                self.send_response(response.status)
                for name, value in response.getheaders():
                    if name.lower() not in {"transfer-encoding", "connection", "server", "date"}:
                        self.send_header(name, value)
                self.send_header("Connection", "close")
                self.end_headers()
                while chunk := response.read(65536):
                    self.wfile.write(chunk)
                self.close_connection = True
            finally:
                connection.close()
        do_POST = do_GET
        do_DELETE = do_GET
        do_PUT = do_GET
        do_PATCH = do_GET
        do_HEAD = do_GET

    if args.receipt.exists():
        raise ValueError("endpoint_proxy_receipt_exists")
    # 原子bind失败时绝不停止该地址上的任何进程。
    server = ThreadingHTTPServer(("127.0.0.1", old.port), Forward)
    save(args.receipt, {"pid": os.getpid(), "endpoint": binding["prior_endpoint"], "target": conf["endpoint"],
        "container_id": conf["container_id"], "scope": "already-started backend test endpoint binding", "status": "READY"})
    print("frozen sandbox test endpoint READY", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
