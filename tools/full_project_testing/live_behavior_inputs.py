"""Exact source/runtime/private-input fingerprints for one serial paid batch."""
from __future__ import annotations

import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
from datetime import datetime, timezone

GENERATED = {"node_modules", "dist", "build", ".git", ".gradle", "__pycache__", ".runtime-logs", ".agent-work-state"}
PRODUCT_FILES = ("dataset.json", "candidates.json", "validation.json", "all-attempts.json",
                 "dataset-derivation.json", "generation-cost.json", "generation-summary.json")


def product_dataset(environment):
    """只接受调用者明确提供的冻结集和预期摘要，不回退历史私有目录。"""
    directory = environment.get("FULL_TEST_PRODUCT_DATASET")
    expected = environment.get("FULL_TEST_PRODUCT_FINGERPRINT")
    if not isinstance(directory, str) or not directory.strip() or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("explicit_product_dataset_and_fingerprint_required")
    root = Path(directory).resolve()
    if not root.is_dir():
        raise ValueError("explicit_product_dataset_directory_missing")
    if any(not (root / name).is_file() for name in ("dataset.json", "candidates.json", "validation.json")):
        raise ValueError("required_product_dataset_input_missing")
    from behavior_dataset import load_dataset
    original = load_dataset(root)
    if original["fingerprint"] != expected:
        raise ValueError("explicit_product_dataset_fingerprint_mismatch")
    return root, original


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def python_sources(repo, entrypoints):
    """Follow every local static import, including imports inside functions."""
    repo = Path(repo).resolve()
    directories = [repo / "tools/full_project_testing", repo / "tools/chat_regression"]
    pending = [repo / path for path in entrypoints]
    selected = set()
    while pending:
        path = pending.pop().resolve()
        if path in selected:
            continue
        if not path.is_file() or not path.is_relative_to(repo):
            raise ValueError("required_python_input_missing")
        selected.add(path)
        tree = ast.parse(path.read_text(encoding="utf-8-sig"), filename=path.name)
        modules = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules.extend(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                modules.append(node.module)
        for module in modules:
            parts = module.split(".")
            choices = [base.joinpath(*parts).with_suffix(".py") for base in [path.parent, *directories]]
            resolved = next((item.resolve() for item in choices if item.is_file()), None)
            if resolved is not None:
                pending.append(resolved)
    return selected


def source_tree(root):
    paths = set()
    if not root.exists():
        return paths
    for directory, children, names in os.walk(root, followlinks=False):
        children[:] = sorted(name for name in children if name not in GENERATED)
        for name in sorted(names):
            path = Path(directory) / name
            if path.is_file():
                paths.add(path.resolve())
    return paths


def product_sources(repo):
    paths = set()
    pending = [repo / "tools/frontend/check-product-sessions.mjs", repo / "tools/full_project_testing/live_behavior_frontend.mjs"]
    while pending:
        path = pending.pop().resolve()
        if path in paths:
            continue
        if not path.is_file() or not path.is_relative_to(repo):
            raise ValueError("required_javascript_input_missing")
        paths.add(path)
        text = path.read_text(encoding="utf-8-sig")
        for relative in re.findall(r"(?:from\s*|import\s*\(\s*|import\s*)['\"](\.[^'\"]+)['\"]", text):
            pending.append(path.parent / relative)
    for name in ("frontend-admin", "frontend-chat", "frontend-shared"):
        root = repo / name
        if not (root / "package.json").is_file():
            raise ValueError("required_frontend_package_input_missing")
        paths.update(source_tree(root / "src"))
        paths.update(source_tree(root / "public"))
        for pattern in ("package*.json", "vite.config.*", "tsconfig*.json", "index.html", ".env*"):
            paths.update(path.resolve() for path in root.glob(pattern) if path.is_file())
    paths.update(path.resolve() for path in repo.glob("*lock*") if path.is_file())
    if (repo / "package.json").is_file():
        paths.add((repo / "package.json").resolve())
    return paths


def node_package(name, base):
    """Resolve package roots through the actual pnpm links and Node ancestor search."""
    base = Path(base).resolve()
    for directory in (base, *base.parents):
        path = directory / "node_modules" / name / "package.json"
        if path.is_file():
            return path.resolve()
    return None


def installed_javascript_inputs(repo, environment):
    pending = [(name, repo / "frontend-admin") for name in ("vite", "@playwright/test", "vue")]
    selected, result, core = set(), {}, None
    while pending:
        name, base = pending.pop()
        package = node_package(name, base)
        if package is None:
            raise ValueError("required_installed_frontend_dependency_missing")
        root = package.parent
        if root in selected:
            continue
        selected.add(root)
        data = json.loads(package.read_text(encoding="utf-8"))
        role = "installed_package:" + str(data.get("name")) + "@" + str(data.get("version"))
        for path in sorted(source_tree(root)):
            result[role + "/" + path.relative_to(root).as_posix()] = file_hash(path)
        for dependency in data.get("dependencies", {}):
            pending.append((dependency, root))
        for dependency in data.get("optionalDependencies", {}):
            if node_package(dependency, root) is not None:
                pending.append((dependency, root))
        if data.get("name") == "playwright-core":
            core = root
    if core is None:
        raise ValueError("actual_playwright_core_dependency_missing")
    browsers_file = core / "browsers.json"
    browsers = json.loads(browsers_file.read_text(encoding="utf-8"))["browsers"]
    configured = environment.get("PLAYWRIGHT_BROWSERS_PATH")
    if configured == "0":
        cache = core / ".local-browsers"
    elif configured:
        cache = Path(configured)
    elif os.name == "nt":
        cache = Path(environment["LOCALAPPDATA"]) / "ms-playwright"
    else:
        cache = Path(environment.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")) / "ms-playwright"
    found = 0
    for browser in browsers:
        if browser.get("name") not in {"chromium", "chromium-headless-shell"}:
            continue
        prefix = browser["name"].replace("-", "_") + "-"
        revision = str(browser["revision"])
        for root in cache.glob(prefix + revision):
            for path in sorted(source_tree(root)):
                result["browser_runtime:" + root.name + "/" + path.relative_to(root).as_posix()] = file_hash(path)
                if path.name in {"chrome.exe", "chrome-headless-shell.exe", "chrome", "headless_shell", "Chromium"}:
                    found += 1
    if not found:
        raise ValueError("actual_chromium_runtime_file_missing")
    return result


def capture_inputs(repo, suite, environment, *, runtime=True, extra_paths=()):
    """Only hashes leave this function; credentials and environment values never do."""
    repo = Path(repo).resolve()
    if suite not in {"core", "stress", "product"}:
        raise ValueError("input_manifest_suite_invalid")
    dataset = product_dataset(environment)[0] if suite == "product" else None
    paths = python_sources(repo, ("tools/full_project_testing/live_behavior.py",
                                "tools/full_project_testing/live_behavior_preflight.py"))
    for extra in extra_paths:
        path = Path(extra).resolve()
        if not path.is_file() or not path.is_relative_to(repo):
            raise ValueError("required_extra_execution_input_missing")
        paths.add(path)
    # Main/product entrypoints use all behavior modules through explicit CLI routing.
    # Include their dynamic family without including unrelated test/report files.
    paths.update(path.resolve() for path in (repo / "tools/chat_regression").glob("behavior_*.py"))
    env_root = repo / ".runtime-logs/full-project-test-20261012/environment"
    for name in ("isolated.env", "provision.json", "environment.json"):
        path = env_root / name
        if not path.is_file():
            raise ValueError("required_private_execution_input_missing")
        paths.add(path.resolve())
    if suite == "product":
        paths.update(product_sources(repo))
    external = {}
    if dataset is not None:
        for name in PRODUCT_FILES:
            path = dataset / name
            if path.is_file():
                external["product_dataset:" + name] = file_hash(path)
    state = json.loads((env_root / "environment.json").read_text(encoding="utf-8-sig"))
    for service in ("institution-service", "platform-service", "gateway"):
        row = state.get("services", {}).get(service)
        if not isinstance(row, dict) or not isinstance(row.get("jar"), str) or not Path(row["jar"]).is_file():
            raise ValueError("required_deployed_service_artifact_missing")
        external["deployed_jar:" + service] = file_hash(Path(row["jar"]))
    if runtime:
        external["runtime:python"] = file_hash(Path(sys.executable))
        if suite == "product":
            node = shutil.which("node", path=environment.get("PATH"))
            if not node:
                raise ValueError("required_node_runtime_missing")
            external["runtime:node"] = file_hash(Path(node))
            external.update(installed_javascript_inputs(repo, environment))
    files = {path.relative_to(repo).as_posix(): file_hash(path) for path in sorted(paths)}
    facts = {"schema_version": 1, "suite": suite,
             "scope": "transitive_python_dynamic_behavior_frontend_source_dataset_private_environment_deployed_jars_runtime",
             "files_sha256": files, "external_inputs_sha256": external,
             "effective_environment_sha256": fingerprint(environment)}
    return {**facts, "fingerprint": fingerprint(facts)}


def write_once(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)


def changed_inputs(baseline, current):
    result = []
    for section in ("files_sha256", "external_inputs_sha256"):
        before, after = baseline.get(section, {}), current.get(section, {})
        result.extend(section + "/" + name for name in sorted(set(before) | set(after)) if before.get(name) != after.get(name))
    if baseline.get("effective_environment_sha256") != current.get("effective_environment_sha256"):
        result.append("effective_environment_sha256")
    return result


class InputManifestMonitor:
    def __init__(self, read, root, *, context=None):
        self.read, self.root, self.context = read, Path(root), context or {}
        self.samples, self.failures, self.changes, self.batch_files = [], [], [], None
        self.last_snapshot, self.last_batch = None, None
        self.baseline = self.read()
        write_once(self.root / "input-manifest-start.json", {"captured_at": datetime.now(timezone.utc).isoformat(),
                   "execution_context": self.context, **self.baseline})

    def freeze_batch(self, *, product=False):
        if self.batch_files is not None:
            return
        names = ("plan.json", "private.json", "resources.json", "configuration.json")
        if product:
            names += ("browser-resources-private.json", *PRODUCT_FILES[:3])
        self.batch_files = {name: file_hash(self.root / name) for name in names}
        if product:
            self.batch_files.update({name: file_hash(self.root / name) if (self.root / name).is_file() else None
                                     for name in PRODUCT_FILES[3:]})
        write_once(self.root / "batch-input-manifest-start.json", {"files_sha256": self.batch_files,
                   "fingerprint": fingerprint(self.batch_files), "scope": "frozen_generated_batch_inputs_before_first_chat"})

    def check(self, phase="before_paid_write"):
        self.last_snapshot, self.last_batch = None, None
        try:
            current = self.read()
            self.last_snapshot = current
            changes = changed_inputs(self.baseline, current)
            if self.batch_files is not None:
                actual = {name: file_hash(self.root / name) if (self.root / name).is_file() else None
                          for name in self.batch_files}
                self.last_batch = actual
                changes += ["batch_input/" + name for name in sorted(self.batch_files) if self.batch_files[name] != actual[name]]
            self.samples.append({"observed_at": datetime.now(timezone.utc).isoformat(), "phase": phase,
                                 "fingerprint": current["fingerprint"]})
            if changes:
                self.changes.append({"phase": phase, "changed_inputs": changes})
            return not self.changes and not self.failures
        except Exception as error:
            kind = type(error).__name__ if isinstance(error, (OSError, ValueError, UnicodeError)) else "unexpected_failure"
            self.failures.append({"phase": phase, "kind": kind})
            return False

    def close(self):
        valid = self.check("after_execution")
        final = self.last_snapshot or {"status": "UNAVAILABLE"}
        write_once(self.root / "input-manifest-end.json", {"captured_at": datetime.now(timezone.utc).isoformat(),
                   "execution_context": self.context, **final})
        if self.batch_files is not None:
            batch = {"files_sha256": self.last_batch, "fingerprint": fingerprint(self.last_batch),
                     "status": "MATCHED" if self.last_batch == self.batch_files else "INVALID_OR_UNAVAILABLE"}
            write_once(self.root / "batch-input-manifest-end.json", batch)
        write_once(self.root / "input-manifest-checks.json", {"status": "MATCHED" if valid else "INVALID_INPUT_DRIFT",
                   "samples": self.samples, "changes": self.changes, "failures": self.failures,
                   "start": "input-manifest-start.json", "end": "input-manifest-end.json",
                   "batch_frozen_before_chat": self.batch_files is not None})
        return valid


def ready_binding(configuration):
    return {key: configuration[key] for key in ("model", "system_agents", "system_agent_models")}


def verify_ready(path, suite, current_inputs, current_configuration):
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if (value.get("schema_version") != 1 or value.get("status") != "READY"
            or value.get("paid_dispatches") != 0 or value.get("run_id") != "full-project-test-20261012"
            or value.get("input_fingerprints", {}).get(suite) != current_inputs["fingerprint"]
            or value.get("configuration_binding") != ready_binding(current_configuration)):
        raise ValueError("actual_preflight_source_or_required_model_binding_drift")
    if suite == "product" and value.get("actual_product_ui", {}).get("status") != "PASS":
        raise ValueError("actual_product_bridge_preflight_required")
    return value
