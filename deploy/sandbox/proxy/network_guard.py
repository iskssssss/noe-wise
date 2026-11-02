"""Mitmproxy 出站策略：只允许公网 HTTP(S)，并将已审核 DNS 结果固定到连接。"""

from __future__ import annotations

import ipaddress
import socket
from collections.abc import Iterable

from mitmproxy import http
from mitmproxy.proxy import server_hooks


BLOCKED_NAMES = {
    "localhost",
    "host.docker.internal",
    "gateway.docker.internal",
    "metadata.google.internal",
}


def resolve_public(host: str, port: int) -> list[str]:
    normalized = host.rstrip(".").lower()
    if normalized in BLOCKED_NAMES or normalized.endswith((".internal", ".local", ".localhost")):
        raise ValueError("blocked hostname")
    try:
        literal = ipaddress.ip_address(normalized.strip("[]"))
        addresses: Iterable[ipaddress.IPv4Address | ipaddress.IPv6Address] = (literal,)
    except ValueError:
        infos = socket.getaddrinfo(normalized, port, type=socket.SOCK_STREAM)
        addresses = tuple(ipaddress.ip_address(info[4][0]) for info in infos)
    unique = list(dict.fromkeys(str(address) for address in addresses))
    if not unique or any(not ipaddress.ip_address(address).is_global for address in unique):
        raise ValueError("non-public destination")
    return unique


class NetworkGuard:
    def http_connect(self, flow: http.HTTPFlow) -> None:
        """只允许到公网 443 的 HTTPS CONNECT，并在建连前固定审核后的地址。"""
        if flow.request.port != 443:
            self._deny(flow, "CONNECT port is not allowed")
            return
        if not self._pin(flow.server_conn, flow.request.pretty_host, flow.request.port):
            self._deny(flow, "CONNECT destination is not public")

    def requestheaders(self, flow: http.HTTPFlow) -> None:
        request = flow.request
        if request.scheme not in {"http", "https"}:
            self._deny(flow, "unsupported scheme")
            return
        expected_port = 80 if request.scheme == "http" else 443
        if request.port != expected_port:
            self._deny(flow, "destination port is not allowed")
            return
        if request.headers.get("upgrade") or request.headers.get("connection", "").lower() == "upgrade":
            self._deny(flow, "protocol upgrade is not allowed")
            return
        if not self._pin(flow.server_conn, request.pretty_host, request.port):
            self._deny(flow, "destination is not public")
            return
        request.headers.pop("proxy-authorization", None)

    def server_connect(self, data: server_hooks.ServerConnectionHookData) -> None:
        """最终连接层再次判定并固定地址，避免其他协议路径绕过 HTTP hook。"""
        host, port = data.server.address
        if port not in {80, 443} or not self._pin(data.server, host, port):
            data.server.error = "EGRESS_DENIED"

    @staticmethod
    def _pin(server, host: str, port: int) -> bool:
        try:
            approved = resolve_public(host, port)
        except (OSError, ValueError):
            return False
        # connection_strategy=lazy 确保尚未连接；固定地址消除二次解析造成的 DNS 重绑定窗口。
        server.address = (approved[0], port)
        if port == 443 and not server.sni:
            server.sni = host.rstrip(".")
        return True

    @staticmethod
    def _deny(flow: http.HTTPFlow, reason: str) -> None:
        flow.response = http.Response.make(
            403,
            b'{"code":"EGRESS_DENIED","message":"sandbox egress denied"}',
            {"content-type": "application/json", "x-sandbox-deny-reason": reason},
        )


addons = [NetworkGuard()]
