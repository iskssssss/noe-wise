#!/usr/bin/env python3
"""在指定隔离沙箱上验证真实取消、强杀恢复和持久幂等；只使用标准库与 Docker CLI。"""

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from datetime import datetime, timedelta, timezone


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("验收 HTTP 不允许重定向")


class Lifecycle:
    ROOT = "/var/lib/skillforge-sandbox"
    INSPECT = "{{json .Id}}\n{{json .Name}}\n{{json .Config.Labels}}\n{{json .Mounts}}\n{{json .NetworkSettings.Ports}}\n{{json .State.Running}}"

    def __init__(self):
        self.endpoint = self.required("SANDBOX_VERIFY_ENDPOINT").rstrip("/")
        self.token = self.required("SANDBOX_VERIFY_TOKEN")
        self.container = self.required("SANDBOX_VERIFY_CONTAINER")
        self.prefix = self.required("SANDBOX_VERIFY_PREFIX")
        self.owner = self.required("SANDBOX_VERIFY_OWNER")
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{1,70}", self.prefix):
            raise RuntimeError("本批 prefix 格式无效")
        if self.container != self.prefix + "-service":
            raise RuntimeError("服务名称必须精确匹配本批 prefix-service")
        expected = hashlib.sha256((self.prefix + "-tasks").encode()).hexdigest()
        if self.owner != expected:
            raise RuntimeError("owner 必须来自本批 prefix-tasks 持久卷")
        url = urllib.parse.urlsplit(self.endpoint)
        if url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port or url.path or url.query or url.fragment or url.username:
            raise RuntimeError("endpoint 必须是无其他部分的 loopback HTTP 地址")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.service_id = None
        self.cleanup_allowed = False
        self.tasks = []
        self.facts = {"scope": "isolated real Docker/HTTP synthetic lifecycle", "status": "running", "scenarios": [], "cleanupErrors": []}
        self.run_tag = uuid.uuid4().hex[:12]

    @staticmethod
    def required(name):
        value = os.environ.get(name)
        if not value:
            raise RuntimeError("缺少进程环境变量 " + name)
        return value

    def docker(self, *args, allow_failure=False):
        result = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8", timeout=25)
        if result.returncode and not allow_failure:
            # 不转储 Docker stderr、容器配置或进程环境。
            raise RuntimeError("Docker 控制命令失败: " + args[0])
        return result.returncode, result.stdout

    def service(self, refresh_endpoint=False):
        _, raw = self.docker("inspect", "--type", "container", "--format", self.INSPECT, self.service_id or self.container)
        identifier, name, labels, mounts, ports, running = [json.loads(line) for line in raw.strip().splitlines()]
        if name != "/" + self.container or (self.service_id and identifier != self.service_id):
            raise RuntimeError("服务不可变身份与本批名称不匹配")
        if not re.fullmatch(r"[0-9a-f]{64}", identifier):
            raise RuntimeError("服务 ID 无效")
        if (labels or {}).get("com.docker.compose.project") != self.prefix or (labels or {}).get("com.docker.compose.service") != "service":
            raise RuntimeError("服务 Compose 项目/服务归属不匹配")
        task_mount = [mount for mount in mounts if mount.get("Destination") == self.ROOT]
        if len(task_mount) != 1 or task_mount[0].get("Type") != "volume" or task_mount[0].get("Name") != self.prefix + "-tasks":
            raise RuntimeError("服务未挂载本批精确任务卷")
        self.service_id = identifier
        if running:
            bindings = (ports or {}).get("8200/tcp") or []
            if len(bindings) != 1 or bindings[0].get("HostIp") != "127.0.0.1":
                raise RuntimeError("服务必须仅发布一个 loopback 8200 端口")
            bound = "http://127.0.0.1:" + bindings[0]["HostPort"]
            if refresh_endpoint:
                self.endpoint = bound
            elif self.endpoint != bound:
                raise RuntimeError("endpoint 与本批服务实际端口不匹配")
        return running

    def http(self, method, path, body=None, content_type="application/json", authorized=True):
        headers = {"Authorization": "Bearer " + (self.token if authorized else "synthetic-invalid-token")}
        if body is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.endpoint + path, data=body, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(16 * 1024 * 1024 + 1)
            if len(payload) > 16 * 1024 * 1024:
                raise RuntimeError("HTTP 响应超过控制容量")
            try:
                decoded = json.loads(payload) if payload else None
            except (ValueError, UnicodeError):
                raise RuntimeError("验收接口未返回 JSON") from None
            return response.code, decoded

    @staticmethod
    def check(condition, message):
        if not condition:
            raise AssertionError(message)

    def wait(self, label, observe, timeout=120):
        deadline = time.monotonic() + timeout
        while True:
            value = observe()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise TimeoutError("条件观察超时: " + label)
            # 节制条件轮询；等待时长不作为断言成立依据。
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))

    def ready(self):
        def observe():
            try:
                if not self.service(refresh_endpoint=True):
                    return False
                status, body = self.http("GET", "/actuator/health")
                return status == 200 and body.get("status") == "UP"
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                return False
        self.wait("本批 service 就绪", observe)

    def task_id(self, suffix):
        return self.prefix + "-" + self.run_tag + "-" + suffix

    def request(self, task_id, gated=True):
        code = """import os, threading
from pathlib import Path
work = Path('/workspace/work')
output = Path('/workspace/output')
with (work / 'execution-count.txt').open('a') as count:
    count.write('started\\n')
    count.flush()
    os.fsync(count.fileno())
(output / 'started.json').write_text('{"started":true}', encoding='utf-8')
"""
        if gated:
            code += "while not (work / 'release').exists():\n    threading.Event().wait(0.05)\n"
        code += "(output / 'finished.txt').write_text('finished-once', encoding='utf-8')\n"
        return {"taskId": task_id, "language": "python", "code": code, "dependencies": [], "inputs": [],
                "scriptPath": None, "programTools": False,
                "deadlineAt": (datetime.now(timezone.utc) + timedelta(minutes=15)).isoformat(),
                "executionPolicy": {"revision": 1, "executionTimeoutSeconds": 600, "dockerControlTimeoutSeconds": 10,
                    "workspaceRetentionHours": 24, "workspaceCleanupIntervalMinutes": 60, "outputCaptureBytes": 4194304,
                    "maxArtifactFiles": 2048, "maxConcurrentExecutions": 4, "memoryLimit": "1g", "cpuLimit": 2, "pidsLimit": 256},
                "outputFiles": ["finished.txt"], "profile": "STANDARD",
                "environmentVersion": None, "inputJson": None}

    @staticmethod
    def archive(request):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("request.json", json.dumps({"execution": request, "artifacts": []}, ensure_ascii=False))
        return data.getvalue()

    def submit(self, request, authorized=True):
        return self.http("POST", "/internal/executions/artifact-inputs", self.archive(request), "application/zip", authorized)

    def detail(self, task_id):
        status, body = self.http("GET", "/internal/executions/detail?" + urllib.parse.urlencode({"taskId": task_id}))
        self.check(status == 200, "detail 状态必须为 200")
        return body

    def cancel(self, task_id):
        self.check(task_id in self.tasks, "只能取消本次脚本登记的任务")
        status, body = self.http("DELETE", "/internal/executions/delete", json.dumps({"taskId": task_id}).encode())
        self.check(status == 200, "cancel 状态必须为 200")
        return body

    def read_file(self, relative):
        self.check(bool(re.fullmatch(r"(?:records|workspaces)/[A-Za-z0-9_.-]+(?:/(?:work|output)/[A-Za-z0-9_.-]+)?", relative)), "只读取本批任务文件")
        script = 'if [ -f "$1" ]; then cat "$1"; else printf __ABSENT__; fi'
        _, value = self.docker("exec", self.service_id, "sh", "-c", script, "sh", self.ROOT + "/" + relative)
        return None if value == "__ABSENT__" else value

    def record(self, task_id):
        raw = self.read_file("records/" + task_id + ".json")
        return json.loads(raw) if raw is not None else None

    def markers(self, task_id):
        base = "workspaces/" + task_id
        count = self.read_file(base + "/work/execution-count.txt")
        return {"count": 0 if count is None else len(count.splitlines()),
                "started": self.read_file(base + "/output/started.json") is not None,
                "finished": self.read_file(base + "/output/finished.txt") is not None}

    def task_containers(self, task_id):
        _, raw = self.docker("ps", "--all", "--no-trunc", "--quiet", "--filter", "label=skillforge.sandbox.managed=true",
                             "--filter", "label=skillforge.sandbox.owner=" + self.owner,
                             "--filter", "label=skillforge.sandbox.task-id=" + task_id)
        result = []
        for identifier in raw.splitlines():
            inspected_status, inspected = self.docker("inspect", "--type", "container", "--format",
                "{{json .Id}}\n{{json .Config.Labels}}\n{{json .State.Running}}", identifier, allow_failure=True)
            if inspected_status:
                # 安装容器可能在枚举后被正式 Runner 删除；重新确认不存在，不能吞 Docker 故障。
                _, remaining = self.docker("ps", "--all", "--quiet", "--filter", "id=" + identifier)
                self.check(not remaining.strip(), "已枚举容器无法核验且仍然存在")
                continue
            actual, labels, running = [json.loads(line) for line in inspected.strip().splitlines()]
            self.check(labels.get("skillforge.sandbox.owner") == self.owner and labels.get("skillforge.sandbox.task-id") == task_id,
                       "任务容器归属不匹配")
            result.append({"id": actual, "running": running, "stage": labels.get("skillforge.sandbox.stage")})
        return result

    def start_task(self, suffix, gated=True):
        task_id = self.task_id(suffix)
        request = self.request(task_id, gated)
        # 即使提交响应丢失，也保留本次唯一任务身份供 finally 查询/取消。
        self.tasks.append(task_id)
        status, body = self.submit(request)
        self.check(status == 202 and body["taskId"] == task_id, "新任务必须返回 202")
        if gated:
            def in_flight():
                detail = self.detail(task_id)
                containers = self.task_containers(task_id)
                markers = self.markers(task_id)
                running = [item for item in containers if item["stage"] == "execute" and item["running"]]
                return running[0] if detail["state"] == "running" and detail["phase"] == "execution" and markers == {"count": 1, "started": True, "finished": False} and len(running) == 1 else False
            container = self.wait("真实在途 " + suffix, in_flight)
        else:
            container = None
            self.wait("新短任务完成 " + suffix, lambda: self.detail(task_id)["state"] == "completed")
            self.check(self.markers(task_id) == {"count": 1, "started": True, "finished": True}, "短任务必须执行恰好一次")
        return task_id, request, container

    def release(self, task_id):
        self.check(task_id in self.tasks, "只能释放本次脚本任务门控")
        self.docker("exec", self.service_id, "sh", "-c", 'printf released > "$1"', "sh",
                    self.ROOT + "/workspaces/" + task_id + "/work/release")

    def assert_idempotent(self, task_id, request, state):
        before = self.record(task_id)
        status, body = self.submit(request)
        self.check(status == 200 and body["state"] == state, "同请求必须返回原持久事实")
        after = self.record(task_id)
        self.check(after == before, "幂等提交不能改变 requestDigest/updatedAt 或其他持久字段")
        changed = copy.deepcopy(request)
        changed["code"] += "\n# deliberately different synthetic request\n"
        status, body = self.submit(changed)
        self.check(status == 409 and body.get("code") == "TASK_ID_REUSED", "同 taskId 改请求必须拒绝")
        self.check(self.record(task_id) == before, "冲突提交不能覆盖持久事实")
        self.check(self.markers(task_id)["count"] == 1, "幂等/冲突请求不得重跑")

    def kill_service(self):
        self.check(self.service(), "强杀前本批 service 必须正在运行")
        self.docker("kill", "--signal", "KILL", self.service_id)
        self.wait("本批 service 已停止", lambda: not self.service())

    def restart_service(self):
        self.service()
        self.docker("start", self.service_id)
        self.ready()

    def run(self):
        self.check(self.service(), "本批 service 必须已运行")
        self.ready()
        self.cleanup_allowed = True
        denied = self.request(self.task_id("unauthorized"))
        status, body = self.submit(denied, authorized=False)
        self.check(status == 401 and body.get("code") == "UNAUTHORIZED", "无效认证必须拒绝")
        status, body = self.http("GET", "/internal/executions/detail?taskId=" + denied["taskId"])
        self.check(status == 404 and body.get("code") == "TASK_NOT_FOUND", "拒绝认证不得登记任务")
        self.check(self.record(denied["taskId"]) is None and not self.task_containers(denied["taskId"]), "拒绝认证不得产生执行事实")
        self.facts["scenarios"].append({"name": "unauthorized-no-execution", "passed": True})

        cancelled, cancel_request, running = self.start_task("cancel")
        result = self.cancel(cancelled)
        self.check((result["state"], result["phase"], result.get("error")) == ("cancelled", "cancelled", "CANCELLED"), "在途取消必须确认 cancelled")
        self.wait("取消容器完全删除", lambda: not self.task_containers(cancelled))
        self.release(cancelled)
        self.assert_idempotent(cancelled, cancel_request, "cancelled")
        cancelled_record = self.record(cancelled)
        self.facts["scenarios"].append({"name": "in-flight-cancel", "container": running, "state": result["state"], "passed": True})

        baseline, _, _ = self.start_task("terminal-before-restart", gated=False)
        baseline_record = self.record(baseline)
        recovering, recovery_request, orphan = self.start_task("recover")
        running_record = self.record(recovering)
        self.check(running_record["state"] == "running" and running_record["containerId"] == orphan["id"], "强杀前必须持久登记真实运行容器")
        self.kill_service()
        self.check(any(item["id"] == orphan["id"] and item["running"] for item in self.task_containers(recovering)), "服务强杀应保留在途任务容器供真实恢复清理")
        self.restart_service()
        result = self.detail(recovering)
        self.check((result["state"], result["phase"], result.get("error")) == ("unknown", "unknown_quiescent", "SERVICE_RESTARTED_RESULT_UNKNOWN"), "强杀恢复必须诚实标为 unknown quiescent")
        self.check(not self.task_containers(recovering), "恢复必须清理原任务容器")
        recovered_record = self.record(recovering)
        self.check(recovered_record["requestDigest"] == running_record["requestDigest"], "恢复不得改写请求身份")
        self.check(self.record(baseline) == baseline_record and self.record(cancelled) == cancelled_record, "恢复不得覆盖已完成/已取消终态")
        self.release(recovering)
        self.assert_idempotent(recovering, recovery_request, "unknown")
        status, body = self.http("GET", "/internal/executions/files?taskId=" + recovering)
        self.check(status == 409 and body.get("code") == "FILES_UNAVAILABLE", "UNKNOWN 不允许下载成果")
        self.facts["scenarios"].append({"name": "forced-service-restart-running-to-unknown", "orphan": orphan, "state": result["state"], "passed": True})

        self.kill_service()
        self.restart_service()
        unknown_record = self.record(recovering)
        self.check(unknown_record["state"] == "unknown" and unknown_record["phase"] == "unknown_quiescent"
                   and unknown_record["error"] == recovered_record["error"] and unknown_record["requestDigest"] == recovered_record["requestDigest"], "已有 UNKNOWN 恢复仍保持未知结果和身份")
        self.check(self.record(baseline) == baseline_record and self.record(cancelled) == cancelled_record, "第二次恢复不得覆盖既有已知终态")
        self.assert_idempotent(recovering, recovery_request, "unknown")
        new_task, _, _ = self.start_task("new-after-restart", gated=False)
        for task_id in (cancelled, recovering):
            self.check(self.markers(task_id) == {"count": 1, "started": True, "finished": False}, "释放门控及新短任务完成后不能出现晚到成果/重跑")
            self.check(not self.task_containers(task_id), "终态旧任务不得出现新执行容器")
        self.check(self.record(cancelled) == cancelled_record and self.record(baseline) == baseline_record, "旧已知终态事实必须稳定")
        self.check(self.record(recovering) == unknown_record, "幂等后 UNKNOWN 事实必须稳定")
        self.facts["scenarios"].append({"name": "unknown-restays-unknown-and-new-task-completes", "newTask": new_task, "passed": True})
        self.facts["tasks"] = [{"taskId": task_id, "state": self.detail(task_id)["state"], "markers": self.markers(task_id),
                               "requestDigest": self.record(task_id)["requestDigest"], "updatedAt": self.record(task_id)["updatedAt"]} for task_id in self.tasks]
        self.facts["status"] = "passed"

    def cleanup(self):
        if not self.service_id or not self.cleanup_allowed:
            return
        try:
            if not self.service():
                self.restart_service()
            for task_id in self.tasks:
                status, body = self.http("GET", "/internal/executions/detail?taskId=" + task_id)
                if status == 200 and (body["state"] in ("queued", "running")
                                      or body["state"] == "unknown" and body["phase"] != "unknown_quiescent"):
                    cancelled = self.cancel(task_id)
                    self.check(cancelled["state"] in ("cancelled", "unknown"), "清理必须停止本次任务")
                    self.wait("清理本次任务容器", lambda: not self.task_containers(task_id))
        except Exception as error:
            self.facts["cleanupErrors"].append({"type": type(error).__name__, "message": str(error).replace(self.token, "[redacted]")})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="独立证据目录，写入 result.json")
    options = parser.parse_args()
    run = None
    failure = None
    facts = {"status": "failed", "failure": {"type": "PreflightError"}}
    try:
        run = Lifecycle()
        run.run()
    except Exception as error:
        failure = error
        if run:
            run.facts["status"] = "failed"
            run.facts["failure"] = {"type": type(error).__name__, "message": str(error).replace(run.token, "[redacted]")}
        else:
            facts["failure"]["message"] = str(error)
    finally:
        if run:
            run.cleanup()
            facts = run.facts
            facts["endpoint"] = run.endpoint
            facts["serviceContainerId"] = run.service_id
            if facts["cleanupErrors"] and not failure:
                facts["status"] = "cleanup_failed"
        options.output.mkdir(parents=True, exist_ok=True)
        (options.output / "result.json").write_text(json.dumps(facts, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Sandbox lifecycle acceptance " + facts["status"] + ": " + str((options.output / "result.json").resolve()))
    return 0 if facts["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
