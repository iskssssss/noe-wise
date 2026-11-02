"""将受控文件清单打包为源码交付物；不执行用户代码。"""
import hashlib
import json
import unicodedata
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")


def bundle_filename(package_name):
    if (not isinstance(package_name, str) or not package_name or package_name != package_name.strip()
            or unicodedata.normalize("NFKC", package_name) != package_name
            or package_name in {".", ".."} or package_name.endswith((".", " "))
            or package_name.lower().endswith(".zip")):
        raise ValueError("package_name 必须是规范化的单文件名，不含 .zip 扩展名")
    if any(ord(char) < 32 or 127 <= ord(char) <= 159 or char in '\\/:*?"<>|' for char in package_name):
        raise ValueError("package_name 不能包含路径或控制字符")
    name = package_name + ".zip"
    stem = package_name.split(".", 1)[0].rstrip(" .").lower()
    if (len(name) > 200 or len(name.encode("utf-8")) > 255
            or stem in {"con", "prn", "aux", "nul", *["com" + str(i) for i in range(1, 10)],
                        *["lpt" + str(i) for i in range(1, 10)]}):
        raise ValueError("package_name 长度超限或使用保留名")
    return name


def safe_path(value):
    if not isinstance(value, str) or not value or value.startswith("/"):
        raise ValueError("文件路径必须是非空相对路径")
    path = PurePosixPath(value)
    if ".." in path.parts or path.name in {"", "."}:
        raise ValueError("文件路径不可包含目录回退")
    return path


def locate_input(filename):
    matches = []
    direct = INPUT_DATA / filename
    if direct.is_file():
        matches.append(direct)
    if INPUT_ARTIFACTS.is_dir():
        matches.extend(
            artifact / filename
            for artifact in sorted(INPUT_ARTIFACTS.iterdir())
            if artifact.is_dir() and (artifact / filename).is_file()
        )
    if len(matches) != 1:
        raise FileNotFoundError(f"/input/data 或 /input/artifacts 中需要且只能提供一个 {filename} 输入文件")
    return matches[0]


def main():
    data = json.loads(locate_input("delivery.json").read_text(encoding="utf-8"))
    files, checks = data.get("files"), data.get("verification")
    if not isinstance(files, list) or not files or not isinstance(checks, list) or not checks:
        raise ValueError("files 和 verification 都必须是非空数组")
    bundle_zip = data.get("bundle_zip", False)
    if not isinstance(bundle_zip, bool):
        raise ValueError("bundle_zip 必须是布尔值")
    package_name = data.get("package_name", "source-package")
    bundle_name = bundle_filename(package_name) if bundle_zip else None
    if bundle_name:
        for item in files:
            if not isinstance(item, dict): raise ValueError("files 项必须是对象")
            source_path = str(safe_path(item.get("path"))).casefold()
            if source_path == bundle_name.casefold() or source_path.startswith(bundle_name.casefold() + "/"):
                raise ValueError("ZIP 文件名不能覆盖输入源码文件或目录")
        if (OUTPUT / bundle_name).exists():
            raise ValueError("ZIP 文件名不能覆盖已有输出")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest, seen = [], set()
    for item in files:
        if not isinstance(item, dict): raise ValueError("files 项必须是对象")
        path = safe_path(item.get("path"))
        content = item.get("content")
        if not isinstance(content, str) or not content: raise ValueError(f"{path} 的 content 必须非空")
        if str(path) in seen: raise ValueError(f"重复文件路径: {path}")
        seen.add(str(path)); target = OUTPUT / path
        encoded = content.encode("utf-8")
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(encoded)
        manifest.append({"path": str(path), "sha256": hashlib.sha256(encoded).hexdigest(), "bytes": len(encoded)})
    patch = data.get("patch", "")
    if not isinstance(patch, str): raise ValueError("patch 必须是字符串")
    if patch:
        (OUTPUT / "change.patch").write_text(patch, encoding="utf-8")
    normalized_checks = []
    for item in checks:
        if not isinstance(item, dict) or any(not isinstance(item.get(key), str) or not item[key].strip() for key in ("check", "result", "evidence")):
            raise ValueError("verification 每项必须包含 check、result、evidence")
        normalized_checks.append(item)
    bundle = None
    if bundle_zip:
        if "manifest.json" in seen or (patch and "change.patch" in seen):
            raise ValueError("ZIP 内容不能占用 manifest.json 或 change.patch 保留路径")
        bundle_files = list(manifest)
        if patch:
            encoded_patch = patch.encode("utf-8")
            bundle_files.append({"path": "change.patch", "sha256": hashlib.sha256(encoded_patch).hexdigest(),
                                 "bytes": len(encoded_patch)})
        bundle_manifest = {"files": bundle_files, "entry": None, "includes_originals": True}
        bundle = OUTPUT / bundle_name
        with ZipFile(bundle, "w", compression=ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps(bundle_manifest, ensure_ascii=False, separators=(",", ":")))
            for item in manifest:
                archive.write(OUTPUT / item["path"], arcname=item["path"])
            if patch:
                archive.write(OUTPUT / "change.patch", arcname="change.patch")
    print(json.dumps({"package_name": package_name, "files": manifest,
                      "patch": "change.patch" if patch else None, "bundle": bundle.name if bundle else None,
                      "verification": normalized_checks}, ensure_ascii=False))


if __name__ == "__main__":
    main()
