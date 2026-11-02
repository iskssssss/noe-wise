"""为全面验收创建独立 PG/Redis 与不可变 JAR 实例；只管理本批拥有的资源。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import urllib.request
from run_suite import REPO, load_environment, save


def docker(*args, input=None):
    """Docker 操作以参数数组执行，不把路径或凭据解释为 shell 文本。"""
    result = subprocess.run(["docker", *args], input=input, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def mapped_port(identity, container_port):
    """Docker 原子分配的回环端口在容器创建完成后读取。"""
    return int(docker("port", identity, str(container_port)).rsplit(":", 1)[-1])


def wait_database(identity):
    """就绪依据 pg_isready 的成功状态；轮询间隔不作为通过依据。"""
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        # initdb 的临时服务只开放 Unix socket；宿主将连接最终 TCP 服务。
        if subprocess.run(["docker", "exec", identity, "pg_isready", "-h", "127.0.0.1", "-U", "skillforge", "-d", "skillforge"], capture_output=True).returncode == 0:
            return
        time.sleep(.5)
    raise RuntimeError("isolated_postgres_not_ready")


def start_service(root, state, name, environment, properties=()):
    """启动独立 JAR 副本并绑定端口0；状态保存 PID、真实端口与就绪事实。"""
    copied = root / f"skillforge-{name}.jar"
    if not copied.exists():
        shutil.copy2(REPO / f"backend-java/skillforge-{name}/build/libs/skillforge-{name}.jar", copied)
    attempt = 1 + len([p for p in root.glob(f"{name}-*.log")])
    log = root / f"{name}-{attempt}.log"
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(["java", "-Xmx768m", "-jar", str(copied), "--server.port=0", *properties],
            cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    state.setdefault("services", {})[name] = {"pid": process.pid, "jar": str(copied), "log": str(log), "status": "STARTING"}
    save(root / "environment.json", state)
    deadline = time.monotonic() + 240
    port = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name}_exited_{process.returncode}; see private service log")
        output = log.read_text(encoding="utf-8", errors="replace")
        matches = re.findall(r"(?:Tomcat|Netty) started on port[: ]+(\d+)", output)
        if matches:
            port = int(matches[-1])
            path = "/actuator/health" if name == "platform-service" else "/health/ready"
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
                    if response.status == 200:
                        break
            except Exception:
                pass
        time.sleep(.5)
    else:
        raise RuntimeError(f"{name}_not_ready; see private service log")
    state["services"][name].update({"port": port, "base_url": f"http://127.0.0.1:{port}", "status": "READY"})
    save(root / "environment.json", state)
    print(f"READY {name} port={port} pid={process.pid}", flush=True)
    return port


def start(root, credentials):
    """创建专有容器、受限应用角色和服务；失败事实及资源身份保留以便精确回收。"""
    root.mkdir(parents=True, exist_ok=False)
    environment = load_environment(credentials)
    batch = "sf-fulltest-" + secrets.token_hex(5)
    database_password = secrets.token_urlsafe(32)
    state = {"batch": batch, "containers": [], "services": {}}
    save(root / "environment.json", state)
    pg = docker("run", "-d", "--name", batch + "-pg", "--label", f"skillforge.fulltest={batch}",
                "-p", "127.0.0.1::5432", "-e", "POSTGRES_USER=skillforge", "-e", f"POSTGRES_PASSWORD={database_password}",
                "-e", "POSTGRES_DB=skillforge", "pgvector/pgvector:pg16", "postgres", "-c", "max_connections=160")
    state["containers"].append(pg)
    save(root / "environment.json", state)
    redis = docker("run", "-d", "--name", batch + "-redis", "--label", f"skillforge.fulltest={batch}",
                   "-p", "127.0.0.1::6379", "redis:7-alpine")
    state["containers"].append(redis)
    save(root / "environment.json", state)
    wait_database(pg)
    pg_port, redis_port = mapped_port(pg, 5432), mapped_port(redis, 6379)
    sql = f"CREATE ROLE skillforge_platform LOGIN BYPASSRLS PASSWORD '{database_password}';\n" \
          "GRANT ALL PRIVILEGES ON DATABASE skillforge TO skillforge_platform;\n" \
          "GRANT ALL ON SCHEMA public TO skillforge_platform;\n" \
          f"CREATE ROLE skillforge_app LOGIN PASSWORD '{database_password}';\n"
    docker("exec", "-i", pg, "psql", "-U", "skillforge", "-d", "skillforge", "-v", "ON_ERROR_STOP=1", input=sql)
    # 平台迁移与首管理员初始化使用独立库owner；完成后机构改用受限role。
    administrator_password = "Sf9!" + secrets.token_urlsafe(22)
    environment.update({"SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/skillforge",
       "SPRING_DATASOURCE_USERNAME": "skillforge", "SPRING_DATASOURCE_PASSWORD": database_password,
       "PLATFORM_DATASOURCE_USERNAME": "skillforge", "PLATFORM_DATASOURCE_PASSWORD": database_password,
       "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(redis_port),
       "SECRET_KEY": secrets.token_hex(32), "PLATFORM_SECRET_KEY": secrets.token_hex(32),
       "PLATFORM_ADMIN_USERNAME": "full-test-admin", "PLATFORM_ADMIN_PASSWORD": administrator_password,
       "PLATFORM_ADMIN_EMAIL": "full-test-admin@example.test", "SKILLFORGE_DEBUG": "true",
       "NACOS_DISCOVERY_ENABLED": "false", "NACOS_CONFIG_ENABLED": "false",
       "ARTIFACT_ROOT": str(root / "artifacts"), "WORKSPACE_ROOT": str(root / "workspace"),
       "FILES_ROOT": str(root / "files"), "SF_PLATFORM_USER": "full-test-admin", "SF_PLATFORM_PASSWORD": administrator_password,
       "SF_USER": "tenant-admin", "SF_PASSWORD": administrator_password, "SF_TENANT": "full-test-a"})
    keys = set(environment) - set(os.environ)
    keys |= {"SDK4_LIVE_MODEL_KEY", "DASHSCOPE_API_KEY", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"}
    def save_env():
        """秘密只写仓库忽略的环境文件，不进入环境json或终端。"""
        (root / "isolated.env").write_text("".join(f"{k}={environment[k]}\n" for k in sorted(keys) if k in environment), encoding="utf-8")
    save_env()
    platform = start_service(root, state, "platform-service", environment)
    sql = "GRANT USAGE ON SCHEMA public TO skillforge_app;\n" \
          "GRANT ALL ON ALL TABLES IN SCHEMA public TO skillforge_app,skillforge_platform;\n" \
          "GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO skillforge_app,skillforge_platform;\n" \
          "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO skillforge_app,skillforge_platform;\n"
    docker("exec", "-i", pg, "psql", "-U", "skillforge", "-d", "skillforge", "-v", "ON_ERROR_STOP=1", input=sql)
    environment["SPRING_DATASOURCE_USERNAME"] = "skillforge_app"
    institution = start_service(root, state, "institution-service", environment, ("--spring.flyway.enabled=false",))
    environment.update({"PLATFORM_URI": f"http://127.0.0.1:{platform}", "INSTITUTION_URI": f"http://127.0.0.1:{institution}",
                        "INSTITUTION_WS_URI": f"ws://127.0.0.1:{institution}"})
    keys |= {"PLATFORM_URI", "INSTITUTION_URI", "INSTITUTION_WS_URI"}
    start_service(root, state, "gateway", environment)
    save_env()


def main():
    """本阶段只创建和显示本批环境；后续清理依据保存的资源身份执行。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--credentials", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_relative_to((REPO / ".runtime-logs").resolve()):
        parser.error("仅允许 .runtime-logs 内独立环境")
    start(root, args.credentials)


if __name__ == "__main__":
    main()
