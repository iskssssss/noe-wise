"""Owned S03 PostgreSQL/Redis fixture: prepare, run typed process lane, cleanup.

Requires an exported current test runtime classpath. Never touches existing databases.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[2]
LABEL = "com.skillforge.runtime-execution-fixture"


def captured_text(result: subprocess.CompletedProcess, secrets_to_redact: tuple[str, ...] = ()) -> str:
    """Keep failed child output even when Windows emits non-UTF8 bytes or a stream is absent."""
    chunks = []
    for value in (result.stdout, result.stderr):
        chunks.append(value.decode("utf-8", errors="backslashreplace") if isinstance(value, bytes) else value or "")
    logged = "".join(chunks)
    for secret in secrets_to_redact:
        logged = logged.replace(secret, "<redacted>")
    return logged


def save(root: Path, state: dict) -> None:
    temporary = root / "fixture.json.writing"
    temporary.write_text(json.dumps(state, indent=2), encoding="utf-8")
    temporary.replace(root / "fixture.json")


def docker(*args: str, environment: dict | None = None) -> str:
    result = subprocess.run(["docker", *args], env=environment, capture_output=True, text=True, encoding="utf-8", errors="backslashreplace")
    if result.returncode:
        # Docker diagnostics may include supplied environment: retain no raw credential output.
        raise RuntimeError("Docker command failed: " + args[0])
    return result.stdout.strip()


def wait_ready(name: str, *command: str) -> None:
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = subprocess.run(["docker", "exec", name, *command], capture_output=True)
        if result.returncode == 0:
            return
        time.sleep(.5)
    raise RuntimeError("Fixture readiness timeout")


def cleanup(root: Path) -> bool:
    state = json.loads((root / "fixture.json").read_text(encoding="utf-8"))
    state["cleanup"] = []
    for name in state["containers"]:
        inspected = subprocess.run(["docker", "inspect", name], capture_output=True, text=True, encoding="utf-8", errors="backslashreplace")
        if inspected.returncode:
            # Confirm absence using a successful inventory; daemon failure is not NOT_FOUND.
            present = docker("ps", "-a", "--format", "{{.Names}}")
            if name in present.splitlines():
                raise RuntimeError("Cannot inspect owned container")
            state["cleanup"].append({"name": name, "status": "NOT_FOUND"})
            continue
        item = json.loads(inspected.stdout)[0]
        if item["Config"].get("Labels", {}).get(LABEL) != state["owner"]:
            raise RuntimeError("Container ownership label mismatch")
        # Exact immutable ID and its anonymous volumes only; no image/prune/named-volume operations.
        docker("rm", "--force", "--volumes", item["Id"])
        remaining = docker("ps", "-a", "--no-trunc", "--format", "{{.ID}}")
        if item["Id"] in remaining.splitlines():
            raise RuntimeError("Owned container still exists")
        state["cleanup"].append({"name": name, "id": item["Id"], "status": "REMOVED"})
        save(root, state)
    if "postgresDataDirectory" in state:
        data_root = Path(state["postgresDataRoot"]).resolve()
        directory = Path(state["postgresDataDirectory"]).resolve()
        # 仅清理本次创建且marker归属一致的准确子目录，不向调用方数据根递归。
        if directory.parent != data_root or directory == data_root:
            raise RuntimeError("PostgreSQL data directory outside owned root")
        if directory.exists():
            marker = directory / "fixture-owner.txt"
            if marker.read_text(encoding="utf-8") != state["owner"]:
                raise RuntimeError("PostgreSQL data directory ownership mismatch")
            shutil.rmtree(directory)
        state["postgresDataCleanup"] = "REMOVED"
    state["status"] = "CLEANED"
    save(root, state)
    return True


def prepare(root: Path, classpath_file: Path, java: str, postgres_data_root: Path | None = None) -> dict:
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    if not classpath:
        raise ValueError("Empty classpath")
    root.mkdir(parents=True, exist_ok=False)
    owner = "noewise-runtime-fixture-" + secrets.token_hex(12)
    suffix = secrets.token_hex(8)
    lane = "runtime_restart"
    database = "sf_s03_" + lane + "_" + suffix
    pg, redis = "sf-runtime-pg-" + suffix, "sf-runtime-redis-" + suffix
    state = {"owner": owner, "status": "PREPARING", "containers": [pg, redis], "database": database}
    save(root, state)
    owner_password, app_password, token = (secrets.token_urlsafe(32) for _ in range(3))
    environment = dict(os.environ)
    environment.update(POSTGRES_USER="sf_fixture_owner", POSTGRES_PASSWORD=owner_password, POSTGRES_DB=database)
    try:
        data_mount: list[str] = []
        if postgres_data_root is not None:
            data_root = postgres_data_root.resolve()
            data_root.mkdir(parents=True, exist_ok=True)
            directory = data_root / suffix
            directory.mkdir(exist_ok=False)
            (directory / "fixture-owner.txt").write_text(owner, encoding="utf-8")
            (directory / "data").mkdir()
            state.update(postgresDataRoot=str(data_root), postgresDataDirectory=str(directory))
            save(root, state)
            data_mount = ["--mount", "type=bind,source=" + str(directory / "data") + ",target=/var/lib/postgresql/data"]
        docker("run", "--detach", "--name", pg, "--label", LABEL + "=" + owner,
               "--publish", "127.0.0.1::5432", "--env", "POSTGRES_USER", "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB",
               *data_mount,
               "pgvector/pgvector:pg16", environment=environment)
        docker("run", "--detach", "--name", redis, "--label", LABEL + "=" + owner,
               "--publish", "127.0.0.1::6379", "redis:7-alpine")
        # The image's initialization server accepts Unix sockets before its real TCP server starts.
        wait_ready(pg, "pg_isready", "-h", "127.0.0.1", "-U", "sf_fixture_owner", "-d", database)
        wait_ready(redis, "redis-cli", "ping")
        pg_port = int(docker("port", pg, "5432/tcp").rsplit(":", 1)[1])
        redis_port = int(docker("port", redis, "6379/tcp").rsplit(":", 1)[1])
        url = f"jdbc:postgresql://127.0.0.1:{pg_port}/{database}"
        environment.update(SF_FIXTURE_JDBC=url, SF_FIXTURE_OWNER="sf_fixture_owner", SF_FIXTURE_OWNER_PASSWORD=owner_password,
                           SF_FIXTURE_APP_PASSWORD=app_password, SF_FIXTURE_LANE=lane, SF_FIXTURE_TOKEN=token,
                           SF_FIXTURE_BOOTSTRAP_REPORT=str(root / "bootstrap.json"))
        # Argfile avoids Windows command-length limits; credentials remain only in child environment.
        argument_file = root / "bootstrap.args"
        argument_file.write_text('--class-path\n"' + classpath.replace('\\', '/') + '"\n"' +
                                 str(REPO / "tools/runtime/ExecutionFixtureBootstrap.java").replace('\\', '/') + '"\n', encoding="utf-8")
        result = subprocess.run([java, "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                                 "@" + str(argument_file)], env=environment, capture_output=True)
        logged = captured_text(result, (owner_password, app_password, token))
        (root / "bootstrap.log").write_text(logged, encoding="utf-8")
        if result.returncode:
            raise RuntimeError("Formal Flyway bootstrap failed; see private bootstrap.log")
        bootstrap = json.loads((root / "bootstrap.json").read_text(encoding="utf-8"))
        # Reuse the current official role checks and grants, including pricing-table write revocations.
        grant_environment = dict(os.environ, SPRING_DATASOURCE_URL=url, SPRING_DATASOURCE_USERNAME="skillforge",
                                 PLATFORM_DATASOURCE_USERNAME="sf_fixture_platform", SPRING_FLYWAY_USER="sf_fixture_owner",
                                 SPRING_FLYWAY_PASSWORD=owner_password)
        empty_env = root / "empty.env"
        empty_env.write_text("", encoding="utf-8")
        result = subprocess.run([sys.executable, "-X", "utf8", "-B", str(REPO / "tools/dev/prepare_database.py"),
                                 "--env-file", str(empty_env), "--container", pg], env=grant_environment, capture_output=True)
        (root / "grants.log").write_text(captured_text(result, (owner_password, app_password, token)), encoding="utf-8")
        if result.returncode:
            raise RuntimeError("Official restricted-role grants failed")
        values = {"SKILLFORGE_RUNTIME_PROCESS_RESTART": "1", "SKILLFORGE_S03_EXTERNAL_JDBC_URL": url,
                  "SKILLFORGE_S03_EXTERNAL_DB_USER": "skillforge", "SKILLFORGE_S03_EXTERNAL_DB_PASSWORD": app_password,
                  "SKILLFORGE_S03_EXTERNAL_REDIS_HOST": "127.0.0.1", "SKILLFORGE_S03_EXTERNAL_REDIS_PORT": str(redis_port),
                  "SKILLFORGE_S03_EXTERNAL_FIXTURE_LANE": lane, "SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN": token,
                  "SKILLFORGE_S03_EXTERNAL_SCHEMA_VERSION": bootstrap["schemaVersion"]}
        (root / "fixture.private.json").write_text(json.dumps(values), encoding="utf-8")
        state.update(status="READY", schemaVersion=bootstrap["schemaVersion"], businessRole="skillforge")
        save(root, state)
        return values
    except BaseException as failure:
        state["prepareFailureType"] = type(failure).__name__
        save(root, state)
        try:
            cleanup(root)
        except Exception as cleanup_failure:
            state["status"] = "CLEANUP_FAILED"
            state["cleanupFailureType"] = type(cleanup_failure).__name__
            save(root, state)
            raise cleanup_failure from failure
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "run", "cleanup"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--gradle-user-home", type=Path)
    parser.add_argument("--postgres-data-root", type=Path,
                        help="Explicitly authorized storage comparison root; only a new owned child is mounted and cleaned")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.action == "cleanup":
        cleanup(root)
        return 0
    if args.classpath_file is None or args.action == "run" and args.gradle_user_home is None:
        parser.error("prepare/run require --classpath-file; run also requires --gradle-user-home")
    values = prepare(root, args.classpath_file.resolve(), args.java, args.postgres_data_root)
    if args.action == "prepare":
        print("READY: " + str(root / "fixture.json"))
        return 0
    try:
        environment = {key: value for key, value in os.environ.items() if not key.startswith("SKILLFORGE_S03_EXTERNAL_")}
        environment.update(values)
        # runtime process fixture and Gradle must use the same Java21 selected by the invoker's JAVA_HOME.
        return subprocess.run([sys.executable, "-X", "utf8", "-B", str(REPO / "tools/runtime/run_execution_acceptance.py"),
                               "process", "--gradle-user-home", str(args.gradle_user_home.resolve()), "--execute",
                               "--output", str(root / "acceptance")], env=environment).returncode
    finally:
        cleanup(root)


if __name__ == "__main__":
    raise SystemExit(main())
