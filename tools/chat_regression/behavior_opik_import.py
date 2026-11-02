"""本机 Opik REST 适配：固定实体身份、分批上传、响应丢失后读回，不重跑 Runtime。"""
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from behavior_contract import digest, require
from behavior_opik_export import atomic_json, encoded, load_bundle
from main import batch_lock
from runner import read

PROJECT = "skillforge-behavior"
MAX_BYTES = 2_000_000
MAX_ITEMS = 80


class OpikError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise OpikError("opik_redirect_forbidden")


class OpikApi:
    def __init__(self, url="http://127.0.0.1:15173/api"):
        endpoint = urllib.parse.urlsplit(url)
        require(endpoint.scheme == "http" and endpoint.hostname in {"127.0.0.1", "::1"}
                and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment
                and endpoint.path.rstrip("/") == "/api", "opik_requires_loopback_api")
        self.url = url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None):
        data = encoded(body) if body is not None else None
        if data is not None:
            require(len(data) <= MAX_BYTES, "opik_single_request_too_large")
        request = urllib.request.Request(self.url + "/v1/private" + path, data=data, method=method,
                                         headers={"Content-Type": "application/json", "Comet-Workspace": "default"})
        try:
            with self.opener.open(request, timeout=45) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as exc:
            if method == "GET" and exc.code == 404:
                return None
            # 不输出可能包含模型正文或凭据的服务端错误 body。
            raise OpikError(f"opik_http_{exc.code}") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise OpikError("opik_transport_error_resume_same_bundle") from None

    def pages(self, path):
        rows, page = [], 1
        while True:
            separator = "&" if "?" in path else "?"
            result = self.request("GET", path + f"{separator}page={page}&size=100")
            require(isinstance(result, dict), "opik_missing_page")
            rows.extend(result["content"])
            if len(rows) >= result["total"]:
                return rows
            require(bool(result["content"]), "opik_incomplete_page")
            page += 1


def uuid7():
    # UUIDv7 的时间和版本位来自本次首次分配；恢复时只读持久映射。
    random = uuid.uuid4().int
    value = (int(time.time() * 1000) << 80) | (7 << 76) | ((random >> 64 & 0xFFF) << 64)
    return str(uuid.UUID(int=value | (2 << 62) | (random & ((1 << 62) - 1))))


def chunks(items, field, extra=None):
    extra = extra or {}
    current = []
    for item in items:
        require(len(encoded({**extra, field: [item]})) <= MAX_BYTES, "opik_single_entity_too_large")
        if current and (len(current) >= MAX_ITEMS or len(encoded({**extra, field: current + [item]})) > MAX_BYTES):
            yield current
            current = []
        current.append(item)
    if current:
        yield current


class Importer:
    def __init__(self, api, state_file):
        self.api, self.path = api, Path(state_file)
        self.state = read(self.path) if self.path.exists() else {"api": api.url, "entities": {}, "imports": {}}
        require(self.state["api"] == api.url, "opik_import_target_mismatch")

    def save(self):
        atomic_json(self.path, self.state)

    def entry(self, key):
        return self.state["entities"].setdefault(key, {"id": uuid7(), "status": "allocated"})

    def identify(self, key, payload, metadata=True):
        entry = self.entry(key)
        payload = {**payload, "id": entry["id"]}
        fingerprint = digest(payload)
        require(entry.get("digest", fingerprint) == fingerprint, "opik_entity_content_conflict")
        entry["digest"] = fingerprint
        if metadata:
            payload["metadata"] = {**payload.get("metadata", {}), "skillforge_payload_digest": fingerprint}
        return payload

    def verify(self, expected, actual):
        require(isinstance(actual, dict) and actual.get("id") == expected["id"], "opik_readback_identity_mismatch")
        for field in ("name", "input", "output", "data", "dataset_item_id", "experiment_id", "trace_id", "parent_span_id"):
            if field in expected:
                require(actual.get(field) == expected[field], "opik_readback_content_conflict")
        if "metadata" in expected:
            require(actual.get("metadata", {}).get("skillforge_payload_digest") == expected["metadata"]["skillforge_payload_digest"],
                    "opik_readback_digest_conflict")

    def entity(self, key, endpoint, payload, metadata=True):
        payload = self.identify(key, payload, metadata)
        entry = self.entry(key)
        self.save()  # 外部写入前固定身份，即使响应丢失也不生成新 ID。
        remote = self.api.request("GET", endpoint + "/" + entry["id"])
        if remote is None:
            entry["status"] = "pending"
            self.save()
            self.api.request("POST", endpoint, payload)
            remote = self.read_back(endpoint + "/" + entry["id"])
        self.verify(payload, remote)
        entry["status"] = "confirmed"
        self.save()
        return remote

    def read_back(self, path):
        for attempt in range(5):
            result = self.api.request("GET", path)
            if result is not None:
                return result
            time.sleep(0.2 * (attempt + 1))
        raise OpikError("opik_readback_not_visible_resume_same_bundle")

    def spans(self, bundle_id, trial, trace_id):
        pairs = []
        for span in trial["spans"]:
            key = f"{bundle_id}/{trial['key']}/span/{span['key']}"
            self.entry(key)
        for span in trial["spans"]:
            key = f"{bundle_id}/{trial['key']}/span/{span['key']}"
            payload = {k: v for k, v in span.items() if k not in {"key", "parent"}}
            payload.update(project_name=PROJECT, trace_id=trace_id, source="experiment")
            if span["parent"]:
                payload["parent_span_id"] = self.entry(f"{bundle_id}/{trial['key']}/span/{span['parent']}")["id"]
            payload = self.identify(key, payload)
            entry = self.entry(key)
            if entry["status"] == "confirmed":
                continue
            if entry["status"] == "pending":
                remote = self.api.request("GET", "/spans/" + entry["id"])
                if remote is not None:
                    self.verify(payload, remote)
                    entry["status"] = "confirmed"
                    continue
            pairs.append((key, payload))
        self.save()
        keys = {payload["id"]: key for key, payload in pairs}
        for chunk in chunks([payload for _, payload in pairs], "spans"):
            for payload in chunk:
                self.entry(keys[payload["id"]])["status"] = "pending"
            self.save()
            self.api.request("POST", "/spans/batch", {"spans": chunk})
            # ClickHouse 接口成功不能替代逐实体可见性；只并行只读确认，写入仍串行。
            with ThreadPoolExecutor(max_workers=8) as pool:
                observed = pool.map(lambda payload: self.read_back("/spans/" + payload["id"]), chunk)
                for payload, remote in zip(chunk, observed):
                    self.verify(payload, remote)
                    self.entry(keys[payload["id"]])["status"] = "confirmed"
            self.save()

    def dataset(self, manifest):
        key = "dataset/" + manifest["dataset_digest"]
        name = "sf-" + manifest["dataset"]["suite_version"] + "-" + manifest["dataset_digest"][:16]
        remote = self.entity(key, "/datasets", {"name": name, "project_name": PROJECT,
                             "description": "Frozen SkillForge cases " + manifest["dataset_digest"]}, metadata=False)
        entry = self.entry(key)
        dataset_id = remote["id"]
        suffix = "?version=" + entry["version_hash"] if entry.get("version_hash") else ""
        existing = {row["id"]: row for row in self.api.pages(f"/datasets/{dataset_id}/items" + suffix)}
        wanted, missing = {}, []
        for case in manifest["dataset"]["cases"]:
            item_key = key + "/case/" + case["id"]
            profile = next(p for p in manifest["dataset"]["profiles"] if p["key"] == case["profile"])
            item = self.identify(item_key, {"source": "sdk", "data": {"case": case, "profile": profile}}, metadata=False)
            wanted[case["id"]] = item
            if item["id"] in existing:
                self.verify(item, existing[item["id"]])
            else:
                require(not entry.get("version_id"), "opik_frozen_dataset_item_missing")
                missing.append(item)
        require(set(existing).issubset({v["id"] for v in wanted.values()}), "opik_unexpected_dataset_items")
        group_id = self.entry(key + "/batch-group")["id"]
        self.save()
        for chunk in chunks(missing, "items", {"dataset_id": dataset_id, "batch_group_id": group_id}):
            self.api.request("PUT", "/datasets/items", {"dataset_id": dataset_id, "batch_group_id": group_id, "items": chunk})
        version_id, version_hash = entry.get("version_id"), entry.get("version_hash")
        if not version_id:
            refreshed = self.read_back("/datasets/" + dataset_id)
            version = refreshed.get("latest_version")
            require(isinstance(version, dict) and version.get("id") and version.get("version_hash"), "opik_dataset_version_required")
            version_id, version_hash = version["id"], version["version_hash"]
        actual = self.api.pages(f"/datasets/{dataset_id}/items?version={version_hash}")
        require(len(actual) == len(wanted), "opik_dataset_count_mismatch")
        for item in wanted.values():
            self.verify(item, next((r for r in actual if r["id"] == item["id"]), None))
        # 所有条目可见且内容一致后才封存版本；部分成功仍允许相同 batch_group 补齐。
        entry["version_id"], entry["version_hash"] = version_id, version_hash
        self.save()
        return name, entry, wanted

    def verify_trial(self, trace_id, trial):
        """204 不是完整导入的唯一依据；读回平台的条目数、评分与原始结论。"""
        for attempt in range(6):
            trace = self.read_back("/traces/" + trace_id)
            scores = {s["name"]: s for s in trace.get("feedback_scores", [])}
            ready = trace.get("span_count") == len(trial["spans"]) and all(
                score["name"] in scores and scores[score["name"]]["value"] == score["value"] for score in trial["scores"])
            if ready:
                require(trace.get("output") == trial["trace"]["output"], "opik_original_result_changed")
                return
            time.sleep(0.25 * (attempt + 1))
        raise OpikError("opik_incomplete_trial_readback")

    def run(self, manifest, trials):
        bundle_id = manifest["bundle_id"]
        receipt = self.state["imports"].setdefault(bundle_id, {"status": "pending", "source_batch": manifest["source"]["batch"]})
        require(receipt.get("manifest_digest", digest(manifest)) == digest(manifest), "opik_bundle_content_conflict")
        receipt["manifest_digest"] = digest(manifest)
        self.save()
        name, dataset, items = self.dataset(manifest)
        experiment = self.entity(bundle_id + "/experiment", "/experiments", {
            "name": manifest["source"]["batch"], "dataset_name": name, "dataset_version_id": dataset["version_id"],
            "project_name": PROJECT, "metadata": {"bundle_id": bundle_id, "skillforge_binding": manifest["report"]["binding"],
            "skillforge_status": manifest["report"]["behavior_status"], "release_eligible": manifest["report"]["release_eligible"],
            "blocking_reasons": manifest["report"]["blocking_reasons"], "authority": manifest["authority"],
            **({"supplemental": manifest["supplemental"]} if "supplemental" in manifest else {})}})
        require(experiment.get("dataset_version_id") == dataset["version_id"], "opik_experiment_version_mismatch")
        for trial in trials:
            prefix = bundle_id + "/" + trial["key"]
            trace = self.entity(prefix + "/trace", "/traces", {**trial["trace"], "project_name": PROJECT})
            self.spans(bundle_id, trial, trace["id"])
            # 评分按 trace/name/source 幂等 upsert；每条仅对应既有代码断言。
            scores = [{**score, "id": trace["id"], "project_name": PROJECT} for score in trial["scores"]]
            for chunk in chunks(scores, "scores"):
                self.api.request("PUT", "/traces/feedback-scores", {"scores": chunk})
            item_key = prefix + "/item"
            payload = self.identify(item_key, {"experiment_id": experiment["id"], "dataset_item_id": items[trial["case_id"]]["id"],
                                             "trace_id": trace["id"], "project_name": PROJECT}, metadata=False)
            self.save()
            remote = self.api.request("GET", "/experiments/items/" + payload["id"])
            if remote is None:
                self.api.request("POST", "/experiments/items", {"experiment_items": [payload]})
                remote = self.read_back("/experiments/items/" + payload["id"])
            self.verify(payload, remote)
            self.verify_trial(trace["id"], trial)
            self.entry(item_key)["status"] = "confirmed"
            receipt["completed_trials"] = receipt.get("completed_trials", [])
            if trial["key"] not in receipt["completed_trials"]:
                receipt["completed_trials"].append(trial["key"])
            self.save()
            print(f"opik_import_trial {trial['key']} verified", flush=True)
        verified_experiment = self.read_back("/experiments/" + experiment["id"])
        require(verified_experiment.get("trace_count") == len(trials), "opik_experiment_count_mismatch")
        receipt.update(status="complete", experiment_id=experiment["id"], dataset_id=dataset["id"],
                       dataset_version_id=dataset["version_id"], trials=len(trials),
                       source_behavior_status=manifest["report"]["behavior_status"], release_eligible=manifest["report"]["release_eligible"])
        self.save()
        return receipt


def import_bundle(folder, url="http://127.0.0.1:15173/api", registry=None):
    manifest, trials = load_bundle(folder)
    api = OpikApi(url)
    registry = Path(registry) if registry else Path(__file__).resolve().parents[2] / ".runtime-logs/opik-import" / digest(api.url)[:16]
    registry.mkdir(parents=True, exist_ok=True)
    with batch_lock(registry):
        importer = Importer(api, registry / "receipts.json")
        return importer.run(manifest, trials)
