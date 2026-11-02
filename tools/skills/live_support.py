"""系统 Skill 实时验收的显式资源与 HTTP 辅助，不初始化业务资源。"""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from urllib.parse import urlparse

import requests
from live_batch import BudgetReservation
from live_environment import validate as validate_environment
from live_auth import current_token

LOGGER = logging.getLogger(__name__)


def load(path, default=None):
    """读取可选验收回执；文件不存在时返回调用者提供的默认值，不补业务种子。"""
    LOGGER.debug("读取 Skill 验收回执: phase=entry")
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default
    finally:
        LOGGER.debug("读取 Skill 验收回执: phase=exit")


def save(path, value):
    """同目录原子替换回执，网络写入前的意图记录由调用者负责。"""
    LOGGER.debug("保存 Skill 验收回执: phase=entry")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        LOGGER.debug("保存 Skill 验收回执: phase=exit")


class Client:
    def __init__(self):
        """只加载 SKILL_LIVE_FIXTURE 指定的已有开发资源及凭据；不登录或创建机构、模型。"""
        LOGGER.debug("加载 Skill 实时验收资源: phase=entry")
        try:
            fixture_path = os.environ.get("SKILL_LIVE_FIXTURE")
            if not fixture_path:
                raise RuntimeError("EXPLICIT_SKILL_LIVE_FIXTURE_REQUIRED")
            fixture = load(Path(fixture_path).resolve())
            self.fixture_path = Path(fixture_path).resolve()
            if not isinstance(fixture, dict):
                raise RuntimeError("SKILL_LIVE_FIXTURE_REQUIRED")
            if not isinstance(fixture.get("database_name"), str) or not fixture["database_name"]:
                raise RuntimeError("EXPLICIT_DATABASE_NAME_REQUIRED")
            self.origins = {}
            for service in ("platform", "institution", "gateway"):
                origin = fixture.get("origins", {}).get(service)
                if not isinstance(origin, str):
                    raise RuntimeError("EXPLICIT_SERVICE_ORIGINS_REQUIRED")
                parsed = urlparse(origin)
                if (parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
                        or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
                    raise RuntimeError("LOCAL_SERVICE_ORIGIN_REQUIRED")
                self.origins[service] = origin.rstrip("/")
            self.environment_scope = validate_environment(fixture, self.origins)
            for key in ("tenant_id", "end_user_id", "model_config_id", "source_agent_id"):
                if not isinstance(fixture.get(key), str) or not fixture[key].isascii() or not fixture[key].isdigit():
                    raise RuntimeError("EXPLICIT_EXISTING_RESOURCE_IDS_REQUIRED")
            for key in ("platform_token", "institution_token"):
                if not isinstance(fixture.get(key), str) or not fixture[key].strip() or "\r" in fixture[key] or "\n" in fixture[key]:
                    raise RuntimeError("EXPLICIT_SERVICE_TOKENS_REQUIRED")
            self.source_agent_id = fixture["source_agent_id"]
            self.state = {"resources": {"model:llm": {"id": fixture["model_config_id"]}, "tenant": {"id": fixture["tenant_id"]}}}
            self.access = {"profile:0": {"end_user_id": fixture["end_user_id"]}}
            self.headers = {"platform": {"X-Platform-Token": "Bearer " + fixture["platform_token"]},
                            "institution": {"Authorization": "Bearer " + fixture["institution_token"]}, "gateway": {}}
            self.live_scope = None
            self._budget = None
        finally:
            LOGGER.debug("加载 Skill 实时验收资源: phase=exit")

    def api(self, service, route, method="GET", body=None, headers=None, files=None):
        """调用明确配置的本地管理 API；未知结果或拒绝不重试，不回显响应载荷及凭据。"""
        LOGGER.debug("调用 Skill 验收 API: phase=entry service=%s method=%s", service, method)
        try:
            if method not in ('GET', 'HEAD'):
                self.ensure_budget(query_only=route == '/chat/runs/cancel')
            if service in ('platform', 'institution'):
                token = current_token(self.fixture_path, service, self.origins[service])
                self.headers[service] = {'X-Platform-Token' if service == 'platform' else 'Authorization': 'Bearer ' + token}
            if method == 'POST' and route == '/chat':
                self.before_chat(body)
            try:
                response = requests.request(method, self.origins[service] + "/api/v1" + route,
                    headers=self.headers[service] | (headers or {}), json=body if not files else None,
                    files=files, timeout=180, allow_redirects=False)
            except requests.RequestException:
                raise RuntimeError("OUTCOME_UNKNOWN " + method) from None
            if response.status_code == 204:
                return None
            try:
                envelope = response.json()
            except ValueError:
                raise RuntimeError("HTTP_" + str(response.status_code)) from None
            if not response.ok or not isinstance(envelope, dict) or envelope.get("code") != "OK":
                raise RuntimeError("HTTP_" + str(response.status_code))
            result = envelope.get("data")
            if method == 'POST' and route == '/chat':
                self.after_chat(body, result)
            return result
        finally:
            LOGGER.debug("调用 Skill 验收 API: phase=exit service=%s method=%s", service, method)

    def ensure_budget(self, query_only=False):
        if not self.live_scope:
            raise RuntimeError('EXPLICIT_LIVE_BATCH_SCOPE_REQUIRED')
        if self._budget is None:
            self._budget = BudgetReservation(self.live_scope)
        self._budget.validate(query_only=query_only)

    def before_chat(self, body):
        self.ensure_budget()
        self._budget.before_chat(body, self)

    def after_chat(self, body, response):
        self._budget.after_chat(body, response)

    def settle_budget(self):
        if not self.live_scope:
            raise RuntimeError('EXPLICIT_LIVE_BATCH_SCOPE_REQUIRED')
        if self._budget is None:
            self._budget = BudgetReservation(self.live_scope)
        self._budget.settle(self)
