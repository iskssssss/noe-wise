"""只接受本批 manifest 与 Docker 实际归属同时确认的隔离实时验收环境。"""
from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse


SERVICE_NAMES = {'platform': 'platform-service', 'institution': 'institution-service', 'gateway': 'gateway'}
INSPECT_FORMAT = ('{"id":{{json .Id}},"name":{{json .Name}},"running":{{json .State.Running}},'
                  '"owner":{{json (index .Config.Labels "skillforge.fulltest")}},'
                  '"database":{{range .Config.Env}}{{if eq (index (split . "=") 0) "POSTGRES_DB"}}'
                  '{{json (index (split . "=") 1)}}{{end}}{{end}},'
                  '"ports":{{json (index .NetworkSettings.Ports "5432/tcp")}}}')


def inspect_database(identity):
    """只读取 ID、归属、数据库名与公开端口；不读取或回显数据库密码。"""
    try:
        result = subprocess.run(['docker', 'inspect', '--format', INSPECT_FORMAT, identity],
                                capture_output=True, text=True, timeout=15, check=True)
        return json.loads(result.stdout)
    except (OSError, ValueError, subprocess.SubprocessError):
        raise RuntimeError('ISOLATED_DATABASE_INSPECTION_UNKNOWN') from None


def validate(fixture, origins):
    """数据库名无环境语义；manifest、实际 label、回环 JDBC 和服务身份共同决定隔离归属。"""
    try:
        manifest_path = Path(fixture['environment_manifest']).resolve(strict=True)
        if manifest_path.name != 'environment.json':
            raise ValueError()
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        batch = manifest['batch']
        identity = fixture['postgres_container_id']
        if (not isinstance(batch, str) or not re.fullmatch(r'sf-fulltest-[0-9a-f]{10}', batch)
                or not isinstance(identity, str) or not re.fullmatch(r'[0-9a-f]{64}', identity)
                or identity not in manifest['containers']):
            raise ValueError()
        for name, service_name in SERVICE_NAMES.items():
            service = manifest['services'][service_name]
            if (service['status'] != 'READY' or origins[name] != service['base_url'].rstrip('/')
                    or urlparse(origins[name]).port != service['port']):
                raise ValueError()
        # 只读取 JDBC URL 一项，秘密留在原私有文件，不进入证据和错误输出。
        urls = [line.split('=', 1)[1] for line in manifest_path.with_name('isolated.env')
                .read_text(encoding='utf-8').splitlines() if line.startswith('SPRING_DATASOURCE_URL=')]
        if len(urls) != 1 or not urls[0].startswith('jdbc:postgresql://'):
            raise ValueError()
        jdbc = urlparse(urls[0][5:])
        if (jdbc.hostname != '127.0.0.1' or not jdbc.port or jdbc.username or jdbc.password
                or jdbc.query or jdbc.fragment or jdbc.path != '/' + fixture['database_name']):
            raise ValueError()
    except (KeyError, TypeError, ValueError, OSError):
        raise RuntimeError('EXPLICIT_OWNED_ISOLATED_ENVIRONMENT_REQUIRED') from None
    observed = inspect_database(identity)
    expected_port = {'HostIp': '127.0.0.1', 'HostPort': str(jdbc.port)}
    if (not isinstance(observed, dict) or observed.get('id') != identity
            or observed.get('owner') != batch or observed.get('name') != '/' + batch + '-pg'
            or observed.get('running') is not True or observed.get('database') != fixture['database_name']
            or observed.get('ports') != [expected_port]):
        raise RuntimeError('ISOLATED_DATABASE_OWNERSHIP_MISMATCH')
    return {'environment_manifest': str(manifest_path), 'batch': batch, 'postgres_container_id': identity}
