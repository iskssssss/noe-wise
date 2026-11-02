"""固定上游源码的 Opik 本机管理入口；只管理独立 Compose project，down 保留卷。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import urllib.request

VERSION = "2.2.82"
COMMIT = "d5459acdc83db1bf634324634a121f028d2f9a10"
PROJECT = "skillforge-opik-poc"
REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / ".runtime-logs" / "opik-2.2.82"
PREFIX = "deployment/docker-compose/"


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "SkillForge-Opik-PoC"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def prepare():
    """按上游 git blob 校验每个下载文件，拒绝目录越界和源码漂移。"""
    upstream = ROOT / "upstream"
    manifest_file = ROOT / "source.json"
    if manifest_file.exists():
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        if manifest["commit"] != COMMIT:
            raise RuntimeError("opik_source_commit_mismatch")
        entries = manifest["files"]
    else:
        tree = json.loads(download(f"https://api.github.com/repos/comet-ml/opik/git/trees/{COMMIT}?recursive=1"))
        if tree.get("truncated"):
            raise RuntimeError("opik_source_tree_incomplete")
        entries = [{"path": x["path"][len(PREFIX):], "sha": x["sha"]} for x in tree["tree"]
                   if x["type"] == "blob" and x["path"].startswith(PREFIX)]
    for entry in entries:
        destination = (upstream / entry["path"]).resolve()
        if not destination.is_relative_to(upstream.resolve()):
            raise RuntimeError("opik_source_path_invalid")
        data = destination.read_bytes() if destination.exists() else download(
            f"https://raw.githubusercontent.com/comet-ml/opik/{COMMIT}/{PREFIX}{entry['path']}")
        blob = b"blob " + str(len(data)).encode() + b"\0" + data
        if hashlib.sha1(blob).hexdigest() != entry["sha"]:
            raise RuntimeError("opik_source_blob_mismatch")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    manifest_file.write_text(json.dumps({"version": VERSION, "commit": COMMIT, "files": entries}, indent=2), encoding="utf-8")


def docker():
    executable = shutil.which("docker")
    if not executable:
        executable = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Docker/Docker/resources/bin/docker.exe")
    return executable


def command(*args, capture=False):
    upstream = ROOT / "upstream/docker-compose.yaml"
    # 不将宿主同名环境变量悄悄带入这个独立测试平台。
    env = os.environ.copy()
    for key in re.findall(r"\$\{([A-Z_][A-Z_0-9]*)", upstream.read_text(encoding="utf-8")):
        env.pop(key, None)
    env["OPIK_VERSION"] = VERSION
    return subprocess.run([docker(), "compose", "-p", PROJECT, "-f", str(upstream), "-f",
                           str(Path(__file__).with_name("compose.override.yaml")), "--profile", "opik", *args],
                          env=env, check=True, capture_output=capture, text=True, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "up", "status", "down"])
    args = parser.parse_args()
    if args.action in {"prepare", "up"}:
        prepare()
        configuration = json.loads(command("config", "--format", "json", capture=True).stdout)
        for name, service in configuration["services"].items():
            for port in service.get("ports", []):
                if name != "frontend" or port.get("host_ip") != "127.0.0.1" or str(port.get("published")) != "15173":
                    raise RuntimeError("opik_unexpected_published_port")
            if service.get("privileged"):
                raise RuntimeError("opik_privileged_service_forbidden")
        (ROOT / "compose-config.sha256").write_text(hashlib.sha256(
            json.dumps(configuration, sort_keys=True).encode()).hexdigest(), encoding="ascii")
    if args.action == "up":
        existing = command("ps", "--format", "json", capture=True).stdout.strip()
        if not existing:
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 15173))
        # 明确服务列表：不启动 Python evaluator、示例生成器或额外遥测服务。
        command("up", "-d", "--no-build", "--wait", "--wait-timeout", "900", "frontend")
        # mc 是正常退出的初始化任务，不能让 Compose --wait 把 exit(0) 当服务失败。
        command("run", "--rm", "--no-deps", "mc")
        image_ids = command("images", "-q", capture=True).stdout.split()
        if image_ids:
            result = subprocess.run([docker(), "image", "inspect", *sorted(set(image_ids))],
                                    capture_output=True, text=True, encoding="utf-8", check=True)
            images = [{"id": i["Id"], "digests": i.get("RepoDigests", [])} for i in json.loads(result.stdout)]
            (ROOT / "images.json").write_text(json.dumps(images, indent=2), encoding="utf-8")
        print("Opik: http://127.0.0.1:15173")
    elif args.action == "status":
        command("ps")
    elif args.action == "down":
        command("down")


if __name__ == "__main__":
    main()
