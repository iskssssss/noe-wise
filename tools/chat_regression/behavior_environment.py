"""真实配置回读和专用部署收据核验；只读，不能用命令行布尔值自证环境安全。"""
from __future__ import annotations

import hashlib
import http.client
import ipaddress
import json
import ntpath
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit

from behavior_contract import digest, issue, require
from client import query, failure_diagnostic

IS_WINDOWS = os.name == "nt"
REQUIRED_SYSTEM_AGENT_PURPOSES = ("context-summary", "memory-extraction", "task-context")


def resolve_model(platform, requested, *, rows=None):
    if rows is None:
        rows = platform.request("GET", "/admin/models")
    require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
            "model_directory_response_invalid")
    matches = [m for m in rows if m.get("enabled") and m.get("kind") == "LLM" and
               requested in {str(m.get("id")), m.get("name"), m.get("model_id")}]
    require(len(matches) == 1, "model_selection_must_resolve_exactly_one_enabled_model")
    require(matches[0].get("credential_configured") is True, "model_credential_not_configured")
    return matches[0]


def required_system_agents(rows):
    """Freeze the unique bindings consumed by the three host purpose callers."""
    require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
            "system_agent_directory_response_invalid")
    selected = []
    # Resolve purpose before checking status: a disabled, missing or ambiguous binding
    # must stop the batch, rather than disappear from a published-only directory filter.
    for purpose in REQUIRED_SYSTEM_AGENT_PURPOSES:
        matches = [row for row in rows if row.get("purpose") == purpose]
        require(len(matches) == 1, "required_system_agent_purpose_must_resolve_exactly_one:" + purpose)
        row = matches[0]
        require(row.get("status") == "published", "required_system_agent_not_published:" + purpose)
        require(isinstance(row.get("published"), dict), "required_system_agent_published_config_missing:" + purpose)
        require(bool(str(row.get("id") or "")), "required_system_agent_id_missing:" + purpose)
        require(type(row.get("revision")) is int and row["revision"] > 0,
                "required_system_agent_revision_invalid:" + purpose)
        require(type(row.get("published_version")) is int and row["published_version"] > 0,
                "required_system_agent_published_version_invalid:" + purpose)
        require(isinstance(row["published"].get("model_config_id"), str)
                and bool(row["published"]["model_config_id"]),
                "required_system_agent_model_binding_missing:" + purpose)
        selected.append({key: row.get(key) for key in (
            "id", "agent_key", "purpose", "execution_mode", "revision",
            "published_version", "published", "status")})
    return sorted(selected, key=lambda row: str(row["id"]))


def configuration(api, platform, resources):
    model_rows = platform.request("GET", "/admin/models")
    model = resolve_model(platform, str(resources["model_id"]), rows=model_rows)
    agents = {}
    for key, resource in resources["agents"].items():
        value = api.request("GET", query("/agents/detail", agentId=resource["id"]))
        agents[key] = {k: value.get(k) for k in ("system_prompt", "role_description", "execution_config",
                      "model_config_id", "config", "mount_set_generation", "mounts", "status", "delegation_config")}
    systems = required_system_agents(platform.request("GET", "/admin/system-agents"))
    system_models = {row["published"]["model_config_id"]: resolve_model(
        platform, row["published"]["model_config_id"], rows=model_rows) for row in systems}
    return {"model": model, "agents": agents, "system_agents": systems,
            "system_agent_models": system_models}


class ConfigurationMonitor:
    """每 30 秒回读，异常只记录分类；不输出可能含私密正文的异常消息。"""
    def __init__(self, read, baseline, interval=30):
        self.read, self.baseline, self.interval = read, digest(baseline), interval
        self.samples, self.reasons, self.failures = [], [], []
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self._run, name="behavior-config-monitor", daemon=True)

    def check(self):
        try:
            snapshot = self.read()
            fingerprint = digest(snapshot)
            with self.lock:
                self.samples.append(fingerprint)
                if fingerprint != self.baseline:
                    self.reasons.append(issue("invalid_config_drift", "observed_configuration_changed"))
        except Exception as error:
            with self.lock:
                self.reasons.append(issue("invalid_environment", "configuration_read_failed"))
                self.failures.append({"observed_at": datetime.now(timezone.utc).isoformat(),
                                      **failure_diagnostic(error)})
        return not self.reasons

    def _run(self):
        while not self.stop.wait(self.interval):
            self.check()

    def start(self, *, check_initial=True):
        if check_initial:
            self.check()
        self.thread.start()

    def close(self):
        self.stop.set()
        self.thread.join(35)
        if self.thread.is_alive():
            self.reasons.append(issue("invalid_environment", "configuration_monitor_not_quiescent"))
        self.check()


_PROCESS_SCRIPT = r"""
$requested = @($args | ForEach-Object { [int]$_ })
$rows = @(Get-CimInstance Win32_Process | Where-Object {
    $requested -contains [int]$_.ProcessId
} | Select-Object ProcessId,CommandLine,ExecutablePath)
ConvertTo-Json -InputObject $rows -Depth 4 -Compress
"""

_LISTENER_SCRIPT = r"""
$requested = @($args | ForEach-Object { [int]$_ })
$selected = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {
    $requested -contains [int]$_.LocalPort
} | Select-Object LocalAddress,LocalPort,OwningProcess)
ConvertTo-Json -InputObject $selected -Depth 4 -Compress
"""

_PROFILE_SCRIPT = r"""
$rows = @(Get-NetFirewallProfile -PolicyStore ActiveStore -ErrorAction Stop |
    Select-Object Name,Enabled,DefaultOutboundAction)
ConvertTo-Json -InputObject $rows -Depth 4 -Compress
"""

_ALLOW_RULE_SCRIPT = r"""
$rows = @(Get-NetFirewallRule -PolicyStore ActiveStore -Enabled True -Direction Outbound -Action Allow -ErrorAction Stop |
    ForEach-Object {
        $rule = $_
        $applications = @($rule | Get-NetFirewallApplicationFilter -ErrorAction Stop)
        $addresses = @($rule | Get-NetFirewallAddressFilter -ErrorAction Stop)
        $ports = @($rule | Get-NetFirewallPortFilter -ErrorAction Stop)
        [PSCustomObject]@{
            Name = $rule.Name
            Programs = @($applications | ForEach-Object { $_.Program })
            RemoteAddresses = @($addresses | ForEach-Object { $_.RemoteAddress })
            Protocols = @($ports | ForEach-Object { $_.Protocol })
            LocalPorts = @($ports | ForEach-Object { $_.LocalPort })
            RemotePorts = @($ports | ForEach-Object { $_.RemotePort })
        }
    })
ConvertTo-Json -InputObject $rows -Depth 6 -Compress
"""

_PROBE_SOURCE = r"""
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class EgressProbe {
    public static void main(String[] args) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(args[0], Integer.parseInt(args[1])), 3000);
            System.exit(41);
        } catch (SocketTimeoutException expected) {
            System.exit(42);
        } catch (ConnectException refused) {
            System.exit(43);
        } catch (Exception other) {
            System.exit(44);
        }
    }
}
"""


def _powershell_json(script, *arguments):
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", "& {\n" + script + "\n}",
             *(str(argument) for argument in arguments)],
            capture_output=True, text=True, timeout=15, check=False)
        require(result.returncode == 0, "environment_system_query_failed")
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError, TypeError):
        raise ValueError("environment_system_query_failed") from None


def _read_process_rows(pids):
    return _powershell_json(_PROCESS_SCRIPT, *sorted(pids))


def _read_listener_rows(ports):
    return _powershell_json(_LISTENER_SCRIPT, *sorted(ports))


def _read_firewall_profiles():
    return _powershell_json(_PROFILE_SCRIPT)


def _read_firewall_rules():
    return _powershell_json(_ALLOW_RULE_SCRIPT)


def _read_sentinel_status(url):
    parsed = urlsplit(url)
    target = parsed.path or "/"
    if parsed.query:
        target += "?" + parsed.query
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=3)
    try:
        connection.request("GET", target, headers={"Accept": "application/json"})
        response = connection.getresponse()
        require(response.status == 200, "sentinel_control_unavailable")
        body = response.read(65537)
        require(len(body) <= 65536, "sentinel_control_response_too_large")
        value = json.loads(body.decode("utf-8"))
        require(isinstance(value, dict), "sentinel_control_response_invalid")
        return value
    except (OSError, http.client.HTTPException, UnicodeError, json.JSONDecodeError):
        raise ValueError("sentinel_control_unavailable") from None
    finally:
        connection.close()


def _run_egress_probe(java_executable, target_host, target_port):
    try:
        with tempfile.TemporaryDirectory(prefix="skillforge-egress-probe-") as directory:
            source = Path(directory) / "EgressProbe.java"
            source.write_text(_PROBE_SOURCE, encoding="utf-8")
            result = subprocess.run(
                [str(java_executable), "--source", "21", str(source), target_host, str(target_port)],
                cwd=directory, capture_output=True, text=True, timeout=15, check=False)
            return result.returncode
    except (OSError, subprocess.SubprocessError):
        raise ValueError("egress_probe_failed") from None


def _rows(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def _values(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def _url_target(value, *, private_non_loopback=False, loopback=False):
    require(isinstance(value, str) and bool(value), "environment_url_required")
    try:
        parsed = urlsplit(value)
        require(parsed.scheme == "http" and parsed.hostname is not None,
                "controlled_http_endpoint_required")
        require(parsed.username is None and parsed.password is None and not parsed.fragment,
                "controlled_http_endpoint_required")
        address = ipaddress.ip_address(parsed.hostname)
        port = parsed.port or 80
    except (ValueError, TypeError):
        raise ValueError("controlled_http_endpoint_required") from None
    require(0 < port <= 65535, "controlled_http_endpoint_required")
    if private_non_loopback:
        require(address.is_private and not address.is_loopback
                and not address.is_link_local and not address.is_unspecified,
                "controlled_private_non_loopback_sentinel_required")
    if loopback:
        require(address.is_loopback, "loopback_sentinel_control_required")
    return parsed, address, port


def _endpoint_port(value):
    try:
        parsed = urlsplit(value)
        require(parsed.scheme in {"http", "https"} and parsed.hostname is not None,
                "environment_endpoint_invalid")
        return parsed.port or (443 if parsed.scheme == "https" else 80)
    except (ValueError, TypeError):
        raise ValueError("environment_endpoint_invalid") from None


def _exact_ip(value, message):
    require(isinstance(value, str) and bool(value), message)
    try:
        require("/" not in value and "-" not in value and value.strip() == value, message)
        return ipaddress.ip_address(value)
    except ValueError:
        raise ValueError(message) from None


def _exact_port(value):
    require(type(value) is int or (isinstance(value, str) and value.isascii() and value.isdigit()),
            "firewall_remote_port_not_exact")
    port = int(value)
    require(0 < port <= 65535, "firewall_remote_port_not_exact")
    return port


def _windows_path(value):
    return ntpath.normcase(ntpath.normpath(str(value).strip().strip('"')))


def _command_runs_artifact(command_line, artifact):
    command = command_line.replace("\\", "/")
    target = str(artifact).replace("\\", "/")
    token = re.escape(target)
    return re.search(r"(?:^|\s)(?:\"" + token + r"\"|'" + token + r"'|" + token + r")(?=\s|$)",
                     command, flags=re.IGNORECASE) is not None


def _validate_firewall_profiles(rows):
    profiles = _rows(rows)
    require(all(isinstance(profile, dict) for profile in profiles),
            "firewall_profile_evidence_invalid")
    enabled = [profile for profile in profiles
               if profile.get("Enabled") is True or profile.get("Enabled") in (1, "True")]
    require(bool(enabled) and len(enabled) == len(profiles), "all_firewall_profiles_must_be_enabled")
    require(all(profile.get("DefaultOutboundAction") in (4, "Block") for profile in enabled),
            "default_deny_egress_not_verified")


def _validate_firewall_rules(rows, java_executables, allowed_remote_ips, allowed_remote_ports):
    normalized_java = {_windows_path(path) for path in java_executables}
    observed_non_loopback = set()
    for rule in _rows(rows):
        require(isinstance(rule, dict), "firewall_rule_evidence_invalid")
        programs = {_windows_path(program) for program in _values(rule.get("Programs"))}
        relevant = "any" in programs or "*" in programs or bool(programs & normalized_java)
        if not relevant:
            continue
        addresses = _values(rule.get("RemoteAddresses"))
        require(bool(addresses), "firewall_remote_address_not_exact")
        for raw_address in addresses:
            address = _exact_ip(raw_address, "firewall_remote_address_not_exact")
            require(address.is_loopback or address in allowed_remote_ips,
                    "firewall_remote_address_not_approved")
            if not address.is_loopback:
                observed_non_loopback.add(address)
        protocols = _values(rule.get("Protocols"))
        require(bool(protocols) and all(str(protocol).lower() in {"6", "tcp"} for protocol in protocols),
                "firewall_protocol_not_tcp")
        remote_ports = _values(rule.get("RemotePorts"))
        require(bool(remote_ports), "firewall_remote_port_not_exact")
        require(all(_exact_port(port) in allowed_remote_ports for port in remote_ports),
                "firewall_remote_port_not_approved")
    require(observed_non_loopback == allowed_remote_ips, "firewall_allowlist_does_not_match_receipt")


def _validated_receipt(receipt, revision, api_base, platform_base):
    require(isinstance(receipt, dict), "environment_receipt_object_required")
    required = {"schema_version", "deployment_revision", "api", "platform", "processes",
                "denied_sentinel_url", "sentinel_ready_url", "sentinel_nonce",
                "allowed_remote_ips", "verified_at"}
    require(required <= set(receipt), "environment_receipt_fields_missing")
    require(type(receipt.get("schema_version")) is int and receipt["schema_version"] == 1
            and receipt.get("deployment_revision") == revision,
            "environment_revision_mismatch")
    require(receipt.get("api") == api_base and receipt.get("platform") == platform_base,
            "environment_endpoint_mismatch")
    require(isinstance(receipt.get("processes"), list) and bool(receipt["processes"]),
            "deployment_process_evidence_required")
    require(isinstance(receipt.get("sentinel_nonce"), str) and bool(receipt["sentinel_nonce"])
            and len(receipt["sentinel_nonce"]) <= 256, "sentinel_nonce_required")
    require(isinstance(receipt.get("allowed_remote_ips"), list), "allowed_remote_ips_required")
    allowed = [_exact_ip(value, "allowed_remote_ip_not_exact") for value in receipt["allowed_remote_ips"]]
    require(len(set(allowed)) == len(allowed), "duplicate_allowed_remote_ip")
    require(all(not address.is_loopback for address in allowed),
            "loopback_must_not_be_declared_as_remote_allowlist")
    try:
        verified_at = datetime.fromisoformat(str(receipt["verified_at"]).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        raise ValueError("environment_receipt_time_invalid") from None
    require(verified_at.tzinfo is not None and verified_at.utcoffset() == timedelta(0),
            "environment_receipt_time_must_be_utc")
    age = datetime.now(timezone.utc) - verified_at.astimezone(timezone.utc)
    require(timedelta(0) <= age <= timedelta(minutes=30), "environment_receipt_stale")
    return set(allowed)


def verify_environment(path, revision, api_base, platform_base):
    """收据引用实际部署文件、进程和系统防火墙；不修改共用主机策略。"""
    try:
        receipt = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ValueError("environment_receipt_unreadable") from None
    allowed_remote_ips = _validated_receipt(receipt, revision, api_base, platform_base)
    require(IS_WINDOWS, "live_environment_requires_dedicated_windows_runner")

    denied_parsed, denied_address, denied_port = _url_target(
        receipt["denied_sentinel_url"], private_non_loopback=True)
    _, _, ready_port = _url_target(receipt["sentinel_ready_url"], loopback=True)
    require(denied_address not in allowed_remote_ips, "denied_sentinel_is_allowlisted")
    api_port, platform_port = _endpoint_port(api_base), _endpoint_port(platform_base)
    require(api_port != platform_port, "two_distinct_api_ports_required")

    expected_processes = {}
    for process in receipt["processes"]:
        require(isinstance(process, dict)
                and {"pid", "artifact_path", "artifact_sha256"} <= set(process),
                "deployment_process_evidence_invalid")
        require(type(process["pid"]) is int and process["pid"] > 0,
                "invalid_process_id")
        require(isinstance(process["artifact_path"], str)
                and isinstance(process["artifact_sha256"], str),
                "deployment_process_evidence_invalid")
        pid = process["pid"]
        require(pid not in expected_processes, "duplicate_process_id")
        artifact_input = Path(process["artifact_path"])
        require(artifact_input.is_absolute() and artifact_input.suffix.lower() == ".jar",
                "deployment_artifact_must_be_absolute_jar")
        try:
            artifact = artifact_input.resolve(strict=True)
            checksum = hashlib.sha256()
            with artifact.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    checksum.update(chunk)
        except OSError:
            raise ValueError("deployment_artifact_unreadable") from None
        require(isinstance(process["artifact_sha256"], str)
                and checksum.hexdigest() == process["artifact_sha256"].lower(),
                "deployment_artifact_changed")
        expected_processes[pid] = artifact

    process_rows = _rows(_read_process_rows(set(expected_processes)))
    actual_processes = {}
    for row in process_rows:
        require(isinstance(row, dict) and type(row.get("ProcessId")) is int,
                "deployment_process_query_invalid")
        pid = row["ProcessId"]
        require(pid in expected_processes and pid not in actual_processes,
                "deployment_process_query_invalid")
        command_line = row.get("CommandLine")
        executable_path = row.get("ExecutablePath")
        require(isinstance(command_line, str)
                and _command_runs_artifact(command_line, expected_processes[pid]),
                "deployment_process_does_not_run_artifact")
        require(isinstance(executable_path, str) and bool(executable_path),
                "deployment_java_executable_missing")
        try:
            executable = Path(executable_path).resolve(strict=True)
        except OSError:
            raise ValueError("deployment_java_executable_unreadable") from None
        require(executable.name.lower() == "java.exe", "deployment_process_is_not_java")
        actual_processes[pid] = executable
    require(set(actual_processes) == set(expected_processes), "deployment_process_not_running")

    listener_rows = _rows(_read_listener_rows({api_port, platform_port}))
    listeners = {api_port: set(), platform_port: set()}
    for row in listener_rows:
        require(isinstance(row, dict), "listener_query_invalid")
        try:
            port, pid = int(row["LocalPort"]), int(row["OwningProcess"])
        except (KeyError, TypeError, ValueError):
            raise ValueError("listener_query_invalid") from None
        if port in listeners:
            listeners[port].add(pid)
    require(all(len(pids) == 1 for pids in listeners.values()), "api_listener_pid_not_unique")
    listener_pids = {next(iter(pids)) for pids in listeners.values()}
    require(listener_pids == set(actual_processes), "api_listener_process_mismatch")

    _validate_firewall_profiles(_read_firewall_profiles())
    permitted_ports = {80, 443, api_port, platform_port, ready_port}
    _validate_firewall_rules(_read_firewall_rules(), set(actual_processes.values()),
                             allowed_remote_ips, permitted_ports)

    status = _read_sentinel_status(receipt["sentinel_ready_url"])
    require(status.get("nonce") == receipt["sentinel_nonce"], "sentinel_nonce_mismatch")
    try:
        status_address = ipaddress.ip_address(status.get("target_host"))
        require(type(status.get("target_port")) is int, "sentinel_target_invalid")
        status_port = status["target_port"]
    except (TypeError, ValueError):
        raise ValueError("sentinel_target_invalid") from None
    require(status_address == denied_address and status_port == denied_port
            and status.get("listening") is True, "sentinel_target_not_ready")

    for java_executable in sorted(set(actual_processes.values()), key=lambda value: str(value).lower()):
        require(_run_egress_probe(java_executable, denied_parsed.hostname, denied_port) == 42,
                "egress_probe_not_blocked_by_timeout")
    return receipt
