"""无 SkillForge 凭据的本地业务 Demo HTTP 客户端。"""
from __future__ import annotations

import json
import logging
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, build_opener

from client import Client, NoRedirect

LOG = logging.getLogger(__name__)


class BusinessClient:
    def __init__(self, origin: str):
        """显式本地业务服务必须有 IPv4 loopback 端口，供同一 HTTP/WS 真实传输。"""
        LOG.debug("初始化业务Demo客户端: phase=entry")
        self.origin = Client(origin).origin
        parsed = urlparse(self.origin)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} or not parsed.port:
            raise ValueError("DEMO_REQUIRES_EXPLICIT_IPV4_LOOPBACK_HTTP_PORT")
        self.port = parsed.port
        self.opener = build_opener(NoRedirect())
        LOG.debug("初始化业务Demo客户端: phase=exit")

    def request(self, path: str, body=None, query=None):
        """业务服务不接收平台、机构或治理认证；网络未知写入结果不自动重发。"""
        LOG.debug("调用业务Demo接口: phase=entry path=%s", path)
        target = path + ("?" + urlencode(query) if query else "")
        raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        headers = {"Accept": "application/json"}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        try:
            with self.opener.open(Request(self.origin + target, data=raw, headers=headers,
                                         method="GET" if raw is None else "POST"), timeout=30) as response:
                return json.load(response)
        except HTTPError as failure:
            raise RuntimeError("DEMO_HTTP_" + str(failure.code)) from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError("DEMO_HTTP_OUTCOME_UNKNOWN") from None
        finally:
            LOG.debug("调用业务Demo接口: phase=exit path=%s", path)
