"""完整机构 bootJar 的私有进程强杀/重启验收；只杀本脚本创建的 Popen。"""
from __future__ import annotations

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from run_suite import REPO, load_environment, save


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition, code):
    if not condition:
        raise AssertionError(code)


def digest(value):
    if isinstance(value, (bytes, bytearray, memoryview)):
        return hashlib.sha256(bytes(value)).hexdigest()
    data = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def wait_for(label, condition, timeout=180):
    """只以可观察事实通过；轮询间隔仅减少压力。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(.2)
    raise AssertionError("deadline_" + label)


def install_fixture_catalog(jar, root):
    """仅向私有 JAR 添加能力元数据测试配置；既有生产 archive entry 字节不变。"""
    name = "com.skillforge.institution.recovery.RecoveryCatalogConfiguration"
    source = root / "RecoveryCatalogConfiguration.java"
    source.write_text('''package com.skillforge.institution.recovery;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
@Configuration(proxyBeanMethods=false)
public class RecoveryCatalogConfiguration {
 @Bean @Primary public ModelProviderCatalog recoveryModelProviderCatalog() {
  var capabilities = new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED,
    SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED);
  var protocol = new ProtocolProfile(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "openai-compatible-chat-v1",
    Set.of(AuthenticationScheme.BEARER_API_KEY), capabilities, VerificationStatus.VERIFIED);
  var service = new ServiceProfile("custom-openai-compatible", "Owned recovery loopback fixture", "custom-v1",
    Availability.SUPPORTED, null, EndpointMode.CUSTOM, null, List.of(protocol));
  ModelProvider fixture = () -> new ProviderPreset("custom", "Owned recovery loopback fixture",
    ProviderCategory.CUSTOM_OR_SELF_HOSTED, List.of(service));
  return new ModelProviderCatalog(List.of(fixture));
 }
}
''', encoding="utf-8")
    libraries, classes = root / "fixture-libraries", root / "fixture-classes"
    libraries.mkdir()
    classes.mkdir()
    with zipfile.ZipFile(jar) as archive:
        existing = {item.filename: hashlib.sha256(archive.read(item)).hexdigest() for item in archive.infolist()}
        for item in archive.infolist():
            if item.filename.startswith("BOOT-INF/lib/") and item.filename.endswith(".jar"):
                (libraries / Path(item.filename).name).write_bytes(archive.read(item))
    compile_result = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(libraries / "*"),
        "-d", str(classes), str(source)], capture_output=True, text=True,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    (root / "fixture-compile.log").write_text(compile_result.stdout + compile_result.stderr, encoding="utf-8")
    require(compile_result.returncode == 0, "test_catalog_compile_failed")
    with zipfile.ZipFile(jar, "a") as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, "BOOT-INF/classes/" + file.relative_to(classes).as_posix())
    with zipfile.ZipFile(jar) as archive:
        require(all(hashlib.sha256(archive.read(key)).hexdigest() == value for key, value in existing.items()),
                "production_jar_entries_changed")
    return {"configuration_class": name, "production_entries_unchanged": True,
            "added_test_configuration_only": True, "fixture_jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}


class Http:
    def __init__(self, base, token=None, platform=False):
        self.base, self.token, self.platform = base.rstrip("/"), token, platform

    def request(self, method, path, body=None, expected=(200, 201, 202)):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["X-Platform-Token" if self.platform else "Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers, method=method)
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=35)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            wire = response.read(16 * 1024 * 1024)
            value = json.loads(wire) if wire else {}
            require(response.status in expected,
                    "http_" + method + "_" + path.split("?")[0] + "_" + str(response.status)
                    + "_" + str(value.get("code", "")))
            return value.get("data", value)


class Provider:
    """NativeDecision 真 HTTP/SSE；记录请求摘要及显式屏障，禁止付费网络调用。"""
    def __init__(self):
        self.calls = []
        self.lock = threading.Lock()
        self.entered, self.release = threading.Event(), threading.Event()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    require(self.path in ("/chat/completions", "/v1/chat/completions"), "provider_path")
                    size = int(self.headers.get("Content-Length", "0"))
                    require(0 < size <= 8 * 1024 * 1024, "provider_body_size")
                    payload = json.loads(self.rfile.read(size))
                    message, finish, category = owner.decision(payload)
                    marker = re.findall(r"RECOVERY:([a-z_]+):([a-z0-9-]+)", json.dumps(payload.get("messages", []), ensure_ascii=False))[-1][1]
                    with owner.lock:
                        owner.calls.append({"ordinal": len(owner.calls) + 1, "category": category,
                            "fixture_marker": marker, "request_sha256": digest(payload), "stream": payload.get("stream", False)})
                    if category == "held_consumed":
                        owner.entered.set()
                        require(owner.release.wait(180), "provider_barrier_deadline")
                    model = payload.get("model", "recovery-local")
                    usage = {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
                    if payload.get("stream"):
                        delta = dict(message)
                        if "tool_calls" in delta:
                            delta["tool_calls"] = [dict(call, index=index) for index, call in enumerate(delta["tool_calls"])]
                        first = {"id": "recovery-fixture", "object": "chat.completion.chunk", "model": model,
                                 "choices": [{"index": 0, "delta": delta}]}
                        last = {"id": "recovery-fixture", "object": "chat.completion.chunk", "model": model,
                                "choices": [{"index": 0, "delta": {}, "finish_reason": finish}], "usage": usage}
                        wire = ("data: " + json.dumps(first, ensure_ascii=False) + "\n\ndata: "
                                + json.dumps(last, ensure_ascii=False) + "\n\ndata: [DONE]\n\n").encode()
                        content_type = "text/event-stream"
                    else:
                        wire = json.dumps({"id": "recovery-fixture", "object": "chat.completion", "model": model,
                            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                            "usage": usage}, ensure_ascii=False).encode()
                        content_type = "application/json"
                    self.send_response(200)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(wire)))
                    self.end_headers()
                    self.wfile.write(wire)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception:
                    self.send_error(500, "controlled_provider_failure")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = False
        self.thread = threading.Thread(target=self.server.serve_forever, name="recovery-http", daemon=False)

    @staticmethod
    def decision(payload):
        messages = payload.get("messages", [])
        text = json.dumps(messages, ensure_ascii=False)
        tags = re.findall(r"RECOVERY:([a-z_]+):([a-z0-9-]+)", text)
        require(tags, "provider_missing_fixture_identity")
        category, marker = tags[-1]
        if category == "human" and "RECOVERY_APPROVED:" not in text:
            names = [tool.get("function", {}).get("name") for tool in payload.get("tools", [])]
            require("sf_agent_action" in names, "provider_native_action_missing")
            return {"role": "assistant", "tool_calls": [{"id": "recovery-human-" + marker,
                "type": "function", "function": {"name": "sf_agent_action",
                    "arguments": json.dumps({"type": "ask_user", "question": "确认恢复后的原始任务？"}, ensure_ascii=False)}}]}, "tool_calls", "ask_user"
        return {"role": "assistant", "content": "恢复验收结果:" + marker}, "stop", category

    @property
    def endpoint(self):
        return "http://127.0.0.1:" + str(self.server.server_port)

    def start(self):
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(10)
        require(not self.thread.is_alive(), "provider_cleanup_incomplete")

    def count(self):
        with self.lock:
            return len(self.calls)


class Database:
    """仅连接本批 loopback PG；所有读取显式按本脚本新 tenant 限定。"""
    def __init__(self, environment):
        import psycopg2
        from psycopg2.extras import RealDictCursor
        url = urllib.parse.urlparse(environment["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
        require(url.scheme == "postgresql" and url.hostname == "127.0.0.1"
                and url.port not in (5432, 5433) and not url.query, "isolated_pg_required")
        self.connection = psycopg2.connect(host=url.hostname, port=url.port, dbname=url.path[1:],
            user=environment["PLATFORM_DATASOURCE_USERNAME"], password=environment["PLATFORM_DATASOURCE_PASSWORD"])
        self.connection.autocommit = True
        self.cursor_type = RealDictCursor
        self.tenant = None

    def rows(self, sql, *parameters):
        require(self.tenant, "private_tenant_required")
        with self.connection.cursor(cursor_factory=self.cursor_type) as query:
            query.execute(sql, (self.tenant, *parameters))
            return [dict(row) for row in query.fetchall()]

    def snapshot(self, run, session):
        runs = self.rows("SELECT run_id,causation_input_id,status,run_revision,record_data FROM agent_runtime_runs WHERE tenant_id=%s AND run_id=%s", run)
        require(len(runs) == 1, "one_original_run_required")
        runs[0]["record_sha256"] = digest(runs[0].pop("record_data"))
        ledger = self.rows("SELECT input_id,run_id,decision_kind,receipt_status,ledger_revision,request_fingerprint,command_data,receipt_data FROM agent_runtime_input_ledger WHERE tenant_id=%s AND run_id=%s ORDER BY input_id", run)
        for row in ledger:
            row["command_sha256"] = digest(row.pop("command_data"))
            row["receipt_sha256"] = digest(row.pop("receipt_data"))
        inbox = self.rows("SELECT input_id,status,inbox_revision,attempts,result_ref,reason_code FROM agent_runtime_session_inbox WHERE tenant_id=%s AND session_id=%s ORDER BY inbox_sequence", session)
        checkpoints = self.rows("SELECT content_hash,schema_id,identity_data,payload_data FROM agent_runtime_session_checkpoints WHERE tenant_id=%s AND run_id=%s ORDER BY content_hash", run)
        for row in checkpoints:
            row["identity_sha256"] = digest(row.pop("identity_data"))
            row["payload_sha256"] = digest(row.pop("payload_data"))
        actions = self.rows("SELECT action_id,action_kind,state,action_revision,attempt,attempt_id,outcome_fact_ref,reason_code FROM agent_runtime_actions WHERE tenant_id=%s AND run_id=%s ORDER BY action_id", run)
        attempts = self.rows("SELECT f.action_id,f.attempt_id,f.call_kind,f.status,f.evidence_revision,f.fact_ref,f.outcome_resolution FROM agent_runtime_action_attempt_facts f JOIN agent_runtime_actions a ON a.tenant_id=f.tenant_id AND a.action_id=f.action_id WHERE f.tenant_id=%s AND a.run_id=%s ORDER BY f.action_id,f.attempt_id,f.evidence_revision", run)
        consumes = self.rows("SELECT p.action_id,p.attempt_id,p.status,p.consume_operation_id,p.started_fact_ref FROM nw_runtime_action_permit_consumptions p JOIN agent_runtime_actions a ON a.tenant_id=p.tenant_id AND a.action_id=p.action_id WHERE p.tenant_id=%s AND a.run_id=%s ORDER BY p.action_id,p.attempt_id", run)
        events = self.rows("SELECT event_id,event_type,session_sequence,payload_canonical_json FROM agent_runtime_session_events WHERE tenant_id=%s AND run_id=%s ORDER BY session_sequence", run)
        for row in events:
            row["payload_sha256"] = digest(row.pop("payload_canonical_json"))
        outbox = self.rows("SELECT id,event_id,'PUBLIC_CHAT'::text AS destination,source_event_id,caused_by_event_id,root_input_id,input_id,trace_id,session_sequence,event_type,created_at::text AS created_at,updated_at::text AS updated_at,payload::text AS payload FROM nw_chat_session_outbox WHERE tenant_id=%s AND runtime_run_id=%s ORDER BY session_sequence", run)
        for row in outbox:
            row["payload_sha256"] = digest(row.pop("payload"))
        return {"run": runs[0], "inputs": ledger, "inbox": inbox, "checkpoints": checkpoints,
                "actions": actions, "attempts": attempts, "consumes": consumes, "events": events, "outbox": outbox,
                "publication": self.publication_state(session)}

    def publication_state(self, session):
        """保留实际宿主投影水位与已提交输出数量，不推断客户端接收或已读。"""
        rows = self.rows("""SELECT p.next_sequence,p.projection_checkpoint_sequence,p.projection_checkpoint_event_id,
            p.projection_checkpoint_policy,p.projection_error_code,
            (SELECT max(e.session_sequence) FROM agent_runtime_session_events e
             WHERE e.tenant_id=p.tenant_id::text AND e.session_id=p.session_id) AS source_sequence,
            (SELECT count(*) FROM nw_chat_session_outbox o WHERE o.tenant_id=p.tenant_id AND o.session_id=p.session_id) AS total
            FROM nw_chat_session_outbox_sequences p WHERE p.tenant_id=%s AND p.session_id=%s""", session)
        return rows[0] if rows else None

    def publication_ready(self, session):
        """消息出现仍可能落后于最后诊断；全部源已投影且存在已提交输出才可冻结。"""
        state = self.publication_state(session)
        return state if publication_is_complete(state) else False

    def run_status(self, run, status):
        rows = self.rows("SELECT status FROM agent_runtime_runs WHERE tenant_id=%s AND run_id=%s", run)
        return bool(rows and rows[0]["status"] == status)

    def require_other_tenants_idle(self):
        active = self.rows("SELECT i.input_id FROM agent_runtime_session_inbox i JOIN nw_tenants t ON t.id::text=i.tenant_id WHERE i.tenant_id<>%s AND t.status='active' AND i.status IN ('QUEUED','PROCESSING') LIMIT 1")
        require(not active, "other_isolated_tenant_has_inflight_input_run_recovery_lane_serially")

    def install_model_fixture(self, model_id, endpoint):
        """新平台模型 ID 独占本夹具；目录生产 API 的 unverified custom 不计为通过。"""
        with self.connection.cursor() as query:
            query.execute("SELECT config FROM nw_platform_model_versions WHERE model_id=%s AND revision=1", (model_id,))
            rows = query.fetchall()
            require(len(rows) == 1, "owned_fixture_publication_required")
            config = rows[0][0]
            if isinstance(config, str):
                config = json.loads(config)
            require(config["name"].startswith("process-recovery-"), "owned_fixture_model_required")
            config.update({"provider_id": "custom", "service_id": "custom-openai-compatible", "profile_version": "custom-v1",
                "endpoint": endpoint, "model_id": "process-recovery-local", "dialect_version": "openai-compatible-chat-v1", "revision": 2})
            wire = json.dumps(config)
            query.execute("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(%s,2,%s::jsonb,'owned-process-recovery-fixture')", (model_id, wire))
            require(query.rowcount == 1, "owned_fixture_version_insert")
            query.execute("UPDATE nw_platform_models SET current_revision=2,draft=jsonb_set(draft,'{config}',%s::jsonb) WHERE id=%s AND current_revision=1", (wire, model_id))
            require(query.rowcount == 1, "owned_fixture_draft_update")

    def terminal(self, run, session):
        if not self.run_status(run, "COMPLETED"):
            return False
        value = self.snapshot(run, session)
        return value if any(event["event_type"] == "run.completed" for event in value["events"]) and any(
            event["event_id"] == "runtime-diagnostic:stream:" + run + ":COMPLETION" for event in value["events"]) and all(
            row["status"] == "COMPLETED" for row in value["inbox"]) and self.publication_ready(session) else False


class OwnedApplication:
    """Popen 所有权即 kill 边界；不读取或终止环境中已有 PID。"""
    def __init__(self, root, source, environment, tenant):
        self.root, self.environment, self.tenant = root, dict(environment), str(tenant)
        self.jar = root / "institution-recovery.jar"
        shutil.copy2(source, self.jar)
        self.jar_sha256 = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.process = None
        self.lifecycle = []
        self.base = None
        for name in ("ARTIFACT_ROOT", "WORKSPACE_ROOT", "FILES_ROOT"):
            self.environment[name] = str(root / name.lower())

    def start(self, workers):
        require(self.process is None or self.process.poll() is not None, "owned_process_already_running")
        log = self.root / ("institution-" + str(len(self.lifecycle) + 1) + ".log")
        properties = ["--server.address=127.0.0.1", "--server.port=0", "--spring.flyway.enabled=false",
            "--agent-runtime.workers.auto-startup=" + str(workers).lower(),
            "--agent-runtime.worker-id=recovery-worker-" + self.root.name]
        if getattr(self, "fixture_catalog", None):
            properties.append("--spring.main.sources=" + self.fixture_catalog["configuration_class"])
        with log.open("w", encoding="utf-8") as stream:
            self.process = subprocess.Popen(["java", "-Xmx512m", "-jar", str(self.jar), *properties],
                cwd=self.root, env=self.environment, stdout=stream, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        item = {"pid": self.process.pid, "workers": workers, "log": str(log), "jar_sha256": self.jar_sha256}
        self.lifecycle.append(item)

        def ready():
            require(self.process.poll() is None, "owned_application_exited")
            matches = re.findall(r"(?:Tomcat|Netty) started on port[: ]+(\d+)", log.read_text(encoding="utf-8", errors="replace"))
            if not matches:
                return False
            port = int(matches[-1])
            require(port not in (8000, 8100), "reserved_port_not_allowed")
            self.base = "http://127.0.0.1:" + str(port)
            try:
                with urllib.request.urlopen(self.base + "/health/ready", timeout=3) as response:
                    return response.status == 200
            except (urllib.error.URLError, TimeoutError):
                return False

        wait_for("owned_application_readiness", ready, 240)
        item["base_url"], item["ready"] = self.base, True
        print("RECOVERY_APP_READY pid=" + str(self.process.pid) + " workers=" + str(workers), flush=True)

    def kill(self, boundary):
        require(self.process is not None and self.process.poll() is None, "only_live_owned_process_can_be_killed")
        pid = self.process.pid
        self.process.kill()
        self.process.wait(timeout=30)
        require(self.process.poll() is not None, "owned_process_not_reaped")
        self.lifecycle[-1].update({"termination": "FORCE_KILL", "boundary": boundary, "exit_code": self.process.returncode})
        print("RECOVERY_APP_KILLED pid=" + str(pid) + " boundary=" + boundary, flush=True)

    def close(self):
        if self.process is not None and self.process.poll() is None:
            self.kill("finally_cleanup")


def unchanged(before, after, keys, code):
    for key in keys:
        require(before[key] == after[key], code + "_" + key)


def outbox_identity(value):
    return [(row["event_id"], row["destination"]) for row in value["outbox"]]


def publication_is_complete(state):
    """禁止空 Outbox 或早于当前来源的 checkpoint 误报静止。"""
    return bool(state and state["source_sequence"] is not None
        and state["projection_checkpoint_sequence"] is not None
        and state["projection_checkpoint_sequence"] >= state["source_sequence"]
        and state["projection_error_code"] is None
        and state["total"] > 0)


def attempt_count(value):
    # 一次 attempt 同时持有 STARTED/revision1 与终态/revision2 两个不可变事实。
    return len({(row["action_id"], row["attempt_id"]) for row in value["attempts"]})


def receipt_identity(receipt):
    require(isinstance(receipt, dict), "receipt_object_required")
    return {key: value for key, value in receipt.items() if key != "replayed"}


class Harness:
    def __init__(self, environment_root, output):
        self.environment_root, self.output = environment_root, output
        self.environment = load_environment(environment_root / "isolated.env")
        self.state = json.loads((environment_root / "environment.json").read_text(encoding="utf-8"))
        require(str(self.state.get("batch", "")).startswith("sf-fulltest-"), "explicit_fulltest_environment_required")
        self.provider = Provider()
        self.database = Database(self.environment)
        self.application = None
        self.api = None
        self.token = None
        self.report = {"schema_version": 1, "status": "RUNNING", "topology": "FULL_BOOTJAR_FORCE_KILL_RESTART_PERSISTENT_POSTGRES",
            "scenarios": [], "external_network": "owned_loopback_model_only", "uncovered": [
                "model request interrupted while already consumed and outcome unknown",
                "tool/MCP consumed side effect interrupted after dispatch", "real paid provider crash recovery"]}
        self.slug = "process-recovery-" + secrets.token_hex(5)

    def platform(self):
        base = self.state["services"]["platform-service"]["base_url"]
        require(urllib.parse.urlparse(base).hostname == "127.0.0.1", "platform_loopback_required")
        auth = Http(base + "/api/v1").request("POST", "/platform/auth/login", {
            "username": self.environment["SF_PLATFORM_USER"], "password": self.environment["SF_PLATFORM_PASSWORD"]})
        return Http(base + "/api/v1", auth["access_token"], True)

    def login(self):
        base = self.application.base + "/api/v1"
        auth = Http(base).request("POST", "/auth/login", {"tenant_slug": self.slug,
            "username": "recovery-admin", "password": self.environment["SF_PASSWORD"]})
        self.api = Http(base, auth["access_token"])

    def setup(self):
        self.provider.start()
        platform = self.platform()
        tenant = platform.request("POST", "/admin/tenants", {"tenant_name": self.slug, "tenant_slug": self.slug,
            "plan_code": "free", "admin_username": "recovery-admin", "admin_password": self.environment["SF_PASSWORD"],
            "admin_email": self.slug + "@example.test"})
        self.tenant = str(tenant.get("id", tenant.get("tenant_id", "")))
        require(self.tenant.isdecimal(), "created_private_tenant_identity_required")
        self.database.tenant = self.tenant
        self.report["tenant_id"] = self.tenant
        source = Path(self.state["services"]["institution-service"]["jar"]).resolve()
        require(source.is_relative_to(self.environment_root) and source.is_file(), "isolated_source_jar_required")
        self.application = OwnedApplication(self.output, source, self.environment, self.tenant)
        source_jar_sha256 = self.application.jar_sha256
        self.application.fixture_catalog = install_fixture_catalog(self.application.jar, self.output)
        self.application.jar_sha256 = self.application.fixture_catalog["fixture_jar_sha256"]
        self.database.require_other_tenants_idle()
        self.application.start(False)
        self.login()
        model = platform.request("POST", "/admin/models", {"name": self.slug + " deterministic model", "kind": "LLM",
            "enabled": True, "provider_id": "deepseek", "service_id": "deepseek-api", "profile_version": "2026-09-13",
            "protocol": "openai-chat-completions", "authentication_scheme": "bearer-api-key",
            "endpoint": "https://api.deepseek.com", "model_id": "deepseek-flash", "api_key": "synthetic-local-fixture-key",
            "context_window_tokens": 32768, "supported_max_output_tokens": 4096, "max_output_tokens": 1024,
            "temperature": 0, "reasoning": "DISABLED", "timeout_seconds": 120,
            "input_price_per_million_cents": "0", "output_price_per_million_cents": "0", "currency": "USD"})
        model = platform.request("POST", "/admin/models/publish", {"id": model["id"], "expected_revision": model["revision"]})
        self.database.install_model_fixture(model["id"], self.provider.endpoint)
        model["revision"] = 2
        self.api.request("PUT", "/settings/model-selections", {"llm_model_config_id": model["id"]})
        agent = self.api.request("POST", "/agents", {"agent_key": self.slug, "name": "完整进程恢复验收",
            "system_prompt": "使用本次受控输入回答；需要确认时先 ask_user，收到明确确认后答复。"})
        self.agent = str(agent["id"])
        self.api.request("POST", "/agents/publish", {"agent_id": self.agent})
        self.user = str(self.api.request("POST", "/chat/end-users", {"display_name": self.slug})["id"])
        self.report["fixture"] = {"agent_id": self.agent, "end_user_id": self.user,
            "model_id": str(model["id"]), "model_revision": model["revision"], "provider": self.provider.endpoint,
            "jar_sha256": self.application.jar_sha256, "source_jar": str(source), "source_jar_sha256": source_jar_sha256}
        self.report["fixture"]["catalog_injection"] = self.application.fixture_catalog
        self.report["fixture"]["model_publication_preparation"] = "test-only direct config of newly created synthetic model; production custom profile API is conservatively unverified"
        self.persist()

    def persist(self):
        self.report["provider_calls"] = list(self.provider.calls)
        self.report["lifecycle"] = self.application.lifecycle if self.application else []
        save(self.output / "result.json", self.report)

    def session(self):
        return self.api.request("POST", "/chat/sessions", {"agent_id": self.agent, "end_user_id": self.user})["session_id"]

    def body(self, session, category, marker):
        input_id = self.slug + "-" + marker
        return {"session_id": session, "agent_id": self.agent, "client_message_id": input_id,
            "content": [{"type": "text", "text": "RECOVERY:" + category + ":" + marker + " 中文复杂输入🧭 " + "abc中文" * 48}],
            "context": {"end_user_id": self.user}}

    def submit(self, body, expected=(200, 202)):
        return self.api.request("POST", "/chat", body, expected)

    def restart(self, boundary, workers=True):
        self.database.require_other_tenants_idle()
        self.application.kill(boundary)
        self.persist()
        self.application.start(workers)
        self.login()

    def history(self, session):
        return self.api.request("GET", "/chat/history?session_id=" + urllib.parse.quote(session))

    def accepted(self):
        sessions = [self.session() for _ in range(3)]
        bodies = [self.body(session, "accepted", "queued-" + str(index)) for index, session in enumerate(sessions)]
        receipts = [self.submit(body) for body in bodies]
        runs = ["run:" + body["client_message_id"] for body in bodies]
        before = [self.database.snapshot(run, session) for run, session in zip(runs, sessions)]
        require(self.provider.count() == 0, "queued_acceptance_must_not_dispatch")
        require(all(len(value["inbox"]) == 1 and value["inbox"][0]["status"] == "QUEUED" for value in before), "three_queued_inbox_required")
        require(all(value["run"]["causation_input_id"] == body["client_message_id"] for value, body in zip(before, bodies)), "original_input_mapping")
        replay = self.submit(bodies[0])
        require(receipt_identity(receipts[0]) == receipt_identity(replay), "queued_receipt_replay")
        conflict = dict(bodies[0], content=[{"type": "text", "text": "changed frozen input"}])
        self.submit(conflict, (409,))
        self.restart("three_accepted_queued_inputs")
        after = [wait_for("accepted_original_auto_recovery", lambda run=run, session=session: self.database.terminal(run, session)) for run, session in zip(runs, sessions)]
        require(self.provider.count() == 3, "accepted_exact_three_provider_calls")
        for old, new in zip(before, after):
            unchanged(old, new, ("inputs",), "accepted_frozen_input")
            require(old["run"]["run_id"] == new["run"]["run_id"], "accepted_original_run")
            require(attempt_count(new) == 1 and len(new["consumes"]) == 1
                    and new["consumes"][0]["status"] == "CONSUMED", "accepted_one_consumed_model_attempt")
        for body, receipt in zip(bodies, receipts):
            require(receipt_identity(self.submit(body)) == receipt_identity(receipt), "accepted_recovered_receipt")
        require(self.provider.count() == 3, "accepted_replay_no_external_call")
        history = [self.history(session) for session in sessions]
        require(all("恢复验收结果:queued-" + str(index) in json.dumps(history[index], ensure_ascii=False) for index in range(3)), "accepted_history_exact_outputs")
        self.report["scenarios"].append({"id": "accepted_inbox", "status": "PASS", "auto_recovery_without_resubmit": True,
            "run_ids": runs, "before": before, "after": after, "provider_calls": 3})
        self.persist()

    def human(self):
        session = self.session()
        body = self.body(session, "human", "human-original")
        receipt = self.submit(body)
        run = "run:" + body["client_message_id"]
        wait_for("human_suspended", lambda: self.database.run_status(run, "SUSPENDED"))
        events = wait_for("human_suspended_event", lambda: self.database.rows(
            "SELECT payload_canonical_json FROM agent_runtime_session_events WHERE tenant_id=%s AND run_id=%s AND event_type='run.suspended'", run))
        pending = json.loads(events[0]["payload_canonical_json"])["outcome"]
        require(pending["reason"] == "AGENT_HITL", "agent_hitl_suspension_required")
        def quiescent_suspension():
            value = self.database.snapshot(run, session)
            return value if all(item["status"] == "COMPLETED" for item in value["inbox"]) and any(
                event["event_id"] == "runtime-diagnostic:stream:" + run + ":COMPLETION" for event in value["events"]) and self.database.publication_ready(session) else False
        before = wait_for("human_suspended_inbox_quiescent", quiescent_suspension)
        self.report["current_boundary"] = {"id": "human_suspended_resume", "before": before,
            "pending_ref": pending["suspendRef"], "checkpoint_revision": pending["checkpointRevision"]}
        self.persist()
        count = self.provider.count()
        require(count == 4 and len(before["checkpoints"]) > 0, "human_one_call_persisted_checkpoint")
        self.restart("human_suspended_checkpoint")
        recovered = self.database.snapshot(run, session)
        self.report["current_boundary"]["recovered_before_resume"] = recovered
        self.persist()
        unchanged(before, recovered, ("run", "inputs", "checkpoints", "actions", "attempts", "consumes", "events", "outbox", "publication"), "suspended_restart_exact")
        require(outbox_identity(before) == outbox_identity(recovered), "suspended_original_outbox_identity")
        require(self.provider.count() == count, "suspended_restart_does_not_advance")
        reply = self.body(session, "human", "human-reply")
        reply["content"] = [{"type": "text", "text": "RECOVERY_APPROVED:human-original"}]
        reply["hitl_response"] = {"execution_id": run, "pending_ref": pending["suspendRef"],
            "checkpoint_revision": str(pending["checkpointRevision"]), "response": "RECOVERY_APPROVED:human-original"}
        stale = dict(reply, client_message_id=self.slug + "-human-stale", hitl_response=dict(reply["hitl_response"],
            checkpoint_revision=str(int(pending["checkpointRevision"]) + 1)))
        self.submit(stale, (409, 422))
        require(self.provider.count() == count, "stale_resume_no_external_call")
        require(not self.database.rows("SELECT input_id FROM agent_runtime_input_ledger WHERE tenant_id=%s AND input_id=%s",
                stale["client_message_id"]), "stale_resume_must_not_admit_input")
        resumed_receipt = self.submit(reply)
        after = wait_for("human_original_completed", lambda: self.database.terminal(run, session))
        require(self.provider.count() == count + 1, "human_one_resume_model_call")
        require(attempt_count(after) == 2 and len(after["consumes"]) == 2
                and all(item["status"] == "CONSUMED" for item in after["consumes"]), "human_exact_two_consumed_attempts")
        require(sum(event["event_type"] == "run.resumed" for event in after["events"]) == 1, "one_committed_resume")
        require(receipt_identity(self.submit(reply)) == receipt_identity(resumed_receipt), "human_resume_receipt_replay")
        require(receipt_identity(self.submit(body)) == receipt_identity(receipt), "human_original_receipt_replay")
        require(self.provider.count() == count + 1, "human_replay_no_external_call")
        self.report["scenarios"].append({"id": "human_suspended_resume", "status": "PASS", "run_id": run,
            "pending_ref": pending["suspendRef"], "checkpoint_revision": pending["checkpointRevision"],
            "before": before, "recovered_before_resume": recovered, "after": after, "provider_calls": 2})
        self.report.pop("current_boundary", None)
        self.persist()

    def terminal(self):
        session = self.session()
        bodies = [self.body(session, "terminal", "terminal-" + str(index)) for index in range(3)]
        receipts, before = [], []
        runs = ["run:" + body["client_message_id"] for body in bodies]
        initial_count = self.provider.count()
        for body, run in zip(bodies, runs):
            receipts.append(self.submit(body))
            before.append(wait_for("terminal_original_completed", lambda run=run: self.database.terminal(run, session)))
        # 终态和输出投影是两个阶段；terminal 已等待来源 checkpoint 与实际已提交 Outbox。
        history = wait_for("terminal_exact_history", lambda: (value if all("恢复验收结果:terminal-" + str(index)
            in json.dumps(value, ensure_ascii=False) for index in range(3)) else False) if (value := self.history(session)) else False)
        before = [self.database.snapshot(run, session) for run in runs]
        require(self.provider.count() == initial_count + 3, "terminal_exact_three_calls")
        self.report["current_boundary"] = {"id": "terminal_persisted_replay", "before": before, "history_before": history}
        self.persist()
        self.restart("three_terminal_persisted_runs")
        after = [self.database.snapshot(run, session) for run in runs]
        self.report["current_boundary"]["after"] = after
        recovered_history = self.history(session)
        self.report["current_boundary"]["history_after"] = recovered_history
        self.persist()
        for old, new in zip(before, after):
            unchanged(old, new, ("run", "inputs", "checkpoints", "actions", "attempts", "consumes", "events", "outbox", "publication"), "terminal_restart_exact")
            require(outbox_identity(old) == outbox_identity(new), "terminal_original_outbox_identity")
        require(recovered_history == history, "terminal_history_exact_after_restart")
        for body, receipt in zip(bodies, receipts):
            require(receipt_identity(self.submit(body)) == receipt_identity(receipt), "terminal_receipt_exact_after_restart")
        require(self.provider.count() == initial_count + 3, "terminal_replay_no_external_call")
        replayed = [self.database.snapshot(run, session) for run in runs]
        for old, new in zip(after, replayed):
            unchanged(old, new, ("run", "inputs", "checkpoints", "actions", "attempts", "consumes", "events", "outbox", "publication"), "terminal_replay_no_new_facts")
        self.report["scenarios"].append({"id": "terminal_persisted_replay", "status": "PASS", "run_ids": runs,
            "before": before, "after": after, "after_replay": replayed, "history_sha256": digest(history), "provider_calls": 3})
        self.report.pop("current_boundary", None)
        self.persist()

    def run(self):
        try:
            self.setup()
            self.accepted()
            self.human()
            self.terminal()
            self.report["status"] = "PASS"
        except Exception as failure:
            self.report["status"] = "FAIL"
            self.report["failure"] = {"type": type(failure).__name__, "code": str(failure) if isinstance(failure, AssertionError) else "private_log_required"}
            raise
        finally:
            if self.application:
                self.application.close()
            self.provider.close()
            if self.database.tenant:
                with self.database.connection.cursor() as query:
                    query.execute("UPDATE nw_tenants SET status='suspended' WHERE id=%s AND slug=%s", (self.tenant, self.slug))
                    require(query.rowcount == 1, "owned_private_tenant_cleanup")
                self.report["tenant_cleanup"] = "SUSPENDED_OWNED_FIXTURE_TENANT"
            self.database.connection.close()
            self.persist()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    root, output = arguments.environment_root.resolve(), arguments.output.resolve()
    allowed = (REPO / ".runtime-logs").resolve()
    require(root.is_relative_to(allowed) and output.is_relative_to(allowed) and root != output, "private_workspace_paths_required")
    output.mkdir(parents=True, exist_ok=False)
    harness = Harness(root, output)
    try:
        harness.run()
    except Exception:
        print(json.dumps({"status": "FAIL", "result": str(output / "result.json")}), flush=True)
        return 1
    print(json.dumps({"status": "PASS", "scenarios": len(harness.report["scenarios"]),
        "force_kills": sum(item.get("boundary") != "finally_cleanup" for item in harness.application.lifecycle),
        "provider_calls": harness.provider.count(), "result": str(output / "result.json")}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
