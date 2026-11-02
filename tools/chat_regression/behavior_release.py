"""从 GitHub 认证 API 核实受保护评测运行与不可替换的公开报告。"""
from __future__ import annotations

import io
import json
import re
import urllib.request
import zipfile

from behavior_contract import require

LIMIT = 16 * 1024 * 1024


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        from urllib.parse import urlsplit
        target = urlsplit(newurl)
        require(target.scheme == "https" and target.hostname and
                (target.hostname.endswith(".blob.core.windows.net") or
                 target.hostname.endswith(".githubusercontent.com") or target.hostname == "api.github.com"),
                "untrusted_artifact_redirect")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if target.hostname != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, token):
        require(bool(token), "github_token_required")
        self.token = token

    def __call__(self, path, binary=False):
        require(path.startswith("/repos/"), "invalid_github_resource")
        request = urllib.request.Request("https://api.github.com" + path, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "SkillForge-behavior-gate"})
        with urllib.request.build_opener(SafeRedirect()).open(request, timeout=30) as response:
            data = response.read(LIMIT + 1)
        require(len(data) <= LIMIT, "github_response_too_large")
        return data if binary else json.loads(data)


def verify_ci(report_bytes, repository, run_id, revision, api):
    """调用者传入候选 revision；仓库和运行号不能取自待验证报告。"""
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository or "") is not None,
            "invalid_ci_repository")
    require(str(run_id).isdigit(), "invalid_ci_run_id")
    prefix = "/repos/" + repository
    run = api(prefix + "/actions/runs/" + str(run_id))
    require(run.get("conclusion") == "success" and run.get("status") == "completed"
            and run.get("event") == "workflow_dispatch" and run.get("head_sha") == revision
            and run.get("path") == ".github/workflows/agent-behavior-eval.yml"
            and run.get("repository", {}).get("full_name") == repository, "ci_run_not_qualified")
    environment = api(prefix + "/environments/agent-eval")
    require(any(rule.get("type") == "required_reviewers" and rule.get("reviewers")
                for rule in environment.get("protection_rules", [])), "ci_environment_not_protected")
    approvals = api(prefix + "/actions/runs/" + str(run_id) + "/approvals")
    require(any(item.get("state") == "approved" and any(e.get("name") == "agent-eval"
                for e in item.get("environments", [])) for item in approvals), "ci_environment_approval_missing")
    listing = api(prefix + "/actions/runs/" + str(run_id) + "/artifacts?per_page=100")
    require(listing.get("total_count", 0) <= 100, "ci_artifact_listing_incomplete")
    artifacts = [a for a in listing.get("artifacts", [])
                 if a.get("name") == "agent-behavior-evaluation-public" and not a.get("expired")]
    require(len(artifacts) == 1, "ci_artifact_missing_or_ambiguous")
    artifact = artifacts[0]
    require(artifact.get("workflow_run", {}).get("head_sha") == revision, "ci_artifact_revision_mismatch")
    raw = api(prefix + "/actions/artifacts/" + str(int(artifact["id"])) + "/zip", binary=True)
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = [i for i in archive.infolist() if i.filename == "summary.json"]
        require(len(entries) == 1 and entries[0].file_size <= LIMIT, "ci_summary_missing_or_oversized")
        require(archive.read(entries[0]) == report_bytes, "ci_artifact_content_mismatch")
    report = json.loads(report_bytes)
    require(str(report.get("binding", {}).get("ci_run_id")) == str(run_id)
            and report["binding"].get("ci_repository") == repository
            and report["binding"].get("ci_run_attempt") == run.get("run_attempt"), "ci_binding_mismatch")
    return {"verified": True, "repository": repository, "run_id": str(run_id), "artifact_id": artifact["id"]}
