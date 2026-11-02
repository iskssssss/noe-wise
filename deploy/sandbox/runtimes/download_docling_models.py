"""镜像构建阶段下载固定版本模型并校验字节；任务运行期不调用此入口。"""
import argparse
import hashlib
import json
import shutil
import urllib.request
from pathlib import Path, PurePosixPath


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    for model in lock["models"]:
        for item in model["files"]:
            path = PurePosixPath(model["directory"]) / item["path"]
            if path.is_absolute() or ".." in path.parts or "\\" in str(path):
                raise ValueError("unsafe_model_path")
            target = root / str(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_suffix(target.suffix + ".part")
            digest = hashlib.sha256(); size = 0
            request = urllib.request.Request(item["url"], headers={"User-Agent": "SkillForge-frozen-model-build/1"})
            with urllib.request.urlopen(request, timeout=120) as source, temporary.open("wb") as output:
                while data := source.read(1024 * 1024):
                    size += len(data)
                    if size > item["bytes"]:
                        raise ValueError("model_size_exceeded")
                    digest.update(data); output.write(data)
            if size != item["bytes"] or digest.hexdigest() != item["sha256"]:
                temporary.unlink(missing_ok=True)
                raise ValueError("model_digest_mismatch: " + str(path))
            temporary.replace(target)
            target.chmod(0o444)
            print("verified " + str(path), flush=True)
    shutil.copyfile(args.lock, root / "models.lock.json")
    (root / "models.lock.json").chmod(0o444)


if __name__ == "__main__":
    main()
