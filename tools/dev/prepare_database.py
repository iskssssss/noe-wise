"""按显式宿主/Flyway 角色准备已有目标库的授权，不创建或迁移数据库。"""
from __future__ import annotations

import argparse
import ipaddress
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

REPO = Path(__file__).resolve().parents[2]


def load_environment(path: Path) -> dict[str, str]:
    """环境文件提供基础值，显式进程环境优先；凭据只传给子进程。"""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
        if match:
            value = match[2].strip()
            if len(value) > 1 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[match[1]] = value
    return values | dict(os.environ)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=REPO / ".env")
    parser.add_argument("--container", help="仅本机映射端口匹配的 PostgreSQL 容器；不指定则使用本机 psql")
    args = parser.parse_args()
    environment = load_environment(args.env_file)
    required = ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "PLATFORM_DATASOURCE_USERNAME",
                "SPRING_FLYWAY_USER", "SPRING_FLYWAY_PASSWORD")
    missing = [key for key in required if not environment.get(key)]
    if missing:
        parser.error("缺少显式配置：" + ", ".join(missing))
    url = environment["SPRING_DATASOURCE_URL"]
    if not url.startswith("jdbc:postgresql://"):
        parser.error("仅支持 jdbc:postgresql://host:port/database")
    target = urlsplit(url.removeprefix("jdbc:"))
    if target.username or target.password or target.query or target.fragment or not target.hostname:
        parser.error("数据库 URL 不得包含凭据、查询参数或 fragment")
    database = unquote(target.path.removeprefix("/"))
    roles = {"migration_owner": environment["SPRING_FLYWAY_USER"],
             "platform_role": environment["PLATFORM_DATASOURCE_USERNAME"],
             "institution_role": environment["SPRING_DATASOURCE_USERNAME"]}
    if not database or "/" in database:
        parser.error("必须指定一个目标数据库")
    if len(set(roles.values())) != 3:
        parser.error("迁移 owner、平台和机构角色必须分别配置")
    if any("\x00" in value or "\n" in value or "\r" in value for value in [database, *roles.values()]):
        parser.error("数据库和角色名称不得包含控制字符")
    password = environment["SPRING_FLYWAY_PASSWORD"]
    child_environment = dict(os.environ, PGPASSWORD=password, PGCLIENTENCODING="UTF8")
    port = target.port or 5432
    command = ["psql", "-X", "-w", "-h", target.hostname, "-p", str(port)]
    if args.container:
        try:
            local = target.hostname == "localhost" or ipaddress.ip_address(target.hostname).is_loopback
        except ValueError:
            local = False
        if not local:
            parser.error("容器入口仅接受根环境中的本机 loopback 数据库地址")
        mapped = subprocess.run(["docker", "port", args.container, "5432/tcp"],
                                check=True, capture_output=True, text=True).stdout.splitlines()
        if not any(line.rsplit(":", 1)[-1] == str(port) for line in mapped):
            parser.error("目标容器的 PostgreSQL 映射端口与环境 URL 不一致")
        command = ["docker", "exec", "-i", "-e", "PGPASSWORD", "-e", "PGCLIENTENCODING", args.container,
                   "psql", "-X", "-w", "-h", "127.0.0.1"]
    command += ["-U", roles["migration_owner"], "-d", database, "-v", "ON_ERROR_STOP=1"]
    for name, value in roles.items():
        command += ["-v", f"{name}={value}"]
    sql = (REPO / "deploy/pg-init/roles/runtime-grants.sql").read_text(encoding="utf-8")
    result = subprocess.run(command, input=sql, text=True, encoding="utf-8", env=child_environment,
                            capture_output=True)
    if result.returncode:
        # psql 失败包含可操作的授权原因；不输出命令环境或数据库密码。
        raise SystemExit(result.stderr.replace(password, "<redacted>"))
    print(f"授权已准备：database={database}, owner={roles['migration_owner']}, "
          f"platform={roles['platform_role']}, institution={roles['institution_role']}")


if __name__ == "__main__":
    main()
