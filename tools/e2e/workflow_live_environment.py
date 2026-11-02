"""独占 Workflow 真实验收环境；只准备本批 PG/Redis，服务由 root 协调启动。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import signal
import time
from datetime import datetime

REPO = Path(__file__).resolve().parents[2]


def save(path: Path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def load_env(path: Path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)', line)
        if match:
            value = match[2].strip()
            if len(value) > 1 and value[0] == value[-1] and value[0] in '\"\'':
                value = value[1:-1]
            result[match[1]] = value
    return result


def docker(*args, content=None, timeout=120):
    result = subprocess.run(['docker', *args], input=content, capture_output=True,
                            text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('docker_command_failed:' + args[0])
    return result.stdout.strip()


def prepare(resume: Path | None = None):
    batch = 'sf-wf-live-' + secrets.token_hex(5)
    root = resume.resolve() if resume else REPO / '.runtime-logs' / ('workflow-live-' + datetime.now().strftime('%Y%m%d-%H%M%S-') + batch[-10:])
    if not root.is_relative_to((REPO / '.runtime-logs').resolve()):
        raise RuntimeError('owned_runtime_directory_required')
    ignored = subprocess.run(['git', 'check-ignore', str(root / 'private.env')], cwd=REPO,
                             capture_output=True, text=True)
    if ignored.returncode != 0:
        raise RuntimeError('private_environment_directory_must_be_git_ignored')
    if resume:
        state = json.loads((root / 'environment.json').read_text(encoding='utf-8'))
        batch = state['batch']
        if state.get('services') or state.get('status') != 'PREPARE_FAILED_OR_UNKNOWN':
            raise RuntimeError('resume_only_failed_dependency_preparation')
        for resource in state['containers'].values():
            owner = docker('inspect', '--format', '{{index .Config.Labels "skillforge.workflow-live"}}', resource['id'])
            if owner != batch:
                raise RuntimeError('owned_container_label_mismatch')
    else:
        root.mkdir(parents=True, exist_ok=False)
        state = {'batch': batch, 'root': str(root), 'containers': {}, 'services': {}, 'status': 'PREPARING'}
    save(root / 'environment.json', state)
    password = load_env(root / 'postgres.private.env')['POSTGRES_PASSWORD'] if resume else secrets.token_urlsafe(36)
    platform_password = 'Wf9!' + secrets.token_urlsafe(24)
    tenant_password = 'Wf8!' + secrets.token_urlsafe(24)
    (root / 'postgres.private.env').write_text(
        'POSTGRES_USER=skillforge\nPOSTGRES_DB=workflow_live\nPOSTGRES_PASSWORD=' + password + '\n', encoding='utf-8')
    try:
        for name, image, port in ([] if resume else [('postgres', 'pgvector/pgvector:pg16', 5432), ('redis', 'redis:7-alpine', 6379)]):
            try:
                docker('image', 'inspect', image)
            except RuntimeError:
                docker('pull', image, timeout=240)
            container_name = batch + '-' + name
            state['containers'][name] = {'name': container_name, 'image': image, 'state': 'DISPATCHING'}
            save(root / 'environment.json', state)
            args = ['run', '-d', '--name', container_name, '--label', 'skillforge.workflow-live=' + batch,
                    '-p', f'127.0.0.1::{port}']
            if name == 'postgres':
                args += ['--env-file', str(root / 'postgres.private.env')]
            identity = docker(*args, image, *(['postgres', '-c', 'max_connections=160'] if name == 'postgres' else []))
            published = docker('port', identity, f'{port}/tcp')
            state['containers'][name].update({'id': identity, 'port': int(published.rsplit(':', 1)[1]), 'state': 'CREATED'})
            save(root / 'environment.json', state)
        pg = state['containers']['postgres']['id']
        deadline = time.monotonic() + 90
        while True:
            try:
                # 容器内 Unix socket 在 initdb 临时实例阶段已可用；只接受正式 TCP 监听。
                with socket.create_connection(('127.0.0.1', state['containers']['postgres']['port']), timeout=2):
                    pass
                docker('exec', pg, 'pg_isready', '-U', 'skillforge', '-d', 'workflow_live', timeout=10)
                docker('exec', pg, 'psql', '-U', 'skillforge', '-d', 'workflow_live', '-Atc', 'SELECT 1', timeout=10)
                break
            except (RuntimeError, OSError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('private_postgres_not_ready') from None
                time.sleep(.3)
        redis = state['containers']['redis']['id']
        if docker('exec', redis, 'redis-cli', 'ping') != 'PONG':
            raise RuntimeError('private_redis_not_ready')
        sql = (
            f"CREATE ROLE skillforge_platform LOGIN BYPASSRLS PASSWORD '{password}';\n"
            f"CREATE ROLE skillforge_app LOGIN PASSWORD '{password}';\n"
            'GRANT ALL PRIVILEGES ON DATABASE workflow_live TO skillforge_platform;\n'
            'GRANT ALL ON SCHEMA public TO skillforge_platform;\n'
            'GRANT USAGE ON SCHEMA public TO skillforge_app;\n'
            'ALTER DEFAULT PRIVILEGES FOR ROLE skillforge IN SCHEMA public GRANT ALL ON TABLES TO skillforge_app,skillforge_platform;\n'
            'ALTER DEFAULT PRIVILEGES FOR ROLE skillforge IN SCHEMA public GRANT ALL ON SEQUENCES TO skillforge_app,skillforge_platform;\n'
            'ALTER DEFAULT PRIVILEGES FOR ROLE skillforge IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO skillforge_app,skillforge_platform;\n'
        )
        docker('exec', '-i', pg, 'psql', '-U', 'skillforge', '-d', 'workflow_live', '-1', '-v', 'ON_ERROR_STOP=1', content=sql)
        jdbc = f"jdbc:postgresql://127.0.0.1:{state['containers']['postgres']['port']}/workflow_live"
        source = load_env(REPO / '.env')
        allowed = {key: value for key, value in source.items() if key in {
            'LLM_API_KEY', 'LLM_API_BASE', 'LLM_MODEL', 'LLM_EMBEDDING_MODEL', 'SDK4_LIVE_MODEL_KEY',
            'SKILLFORGE_DEEPSEEK_API_KEY', 'DEEPSEEK_API_KEY', 'DASHSCOPE_API_KEY', 'SKILLFORGE_BAILIAN_API_KEY'}}
        env = {
            **allowed,
            'SPRING_DATASOURCE_URL': jdbc, 'SPRING_DATASOURCE_USERNAME': 'skillforge_app',
            'SPRING_DATASOURCE_PASSWORD': password, 'PLATFORM_DATASOURCE_USERNAME': 'skillforge_platform',
            'PLATFORM_DATASOURCE_PASSWORD': password, 'SPRING_FLYWAY_URL': jdbc,
            'SPRING_FLYWAY_USER': 'skillforge', 'SPRING_FLYWAY_PASSWORD': password,
            'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(state['containers']['redis']['port']), 'REDIS_DATABASE': '0',
            'SECRET_KEY': secrets.token_hex(32), 'PLATFORM_SECRET_KEY': secrets.token_hex(32),
            'SECRET_KEY_DECRYPTION_KEYS': '', 'PLATFORM_ADMIN_USERNAME': batch + '-platform',
            'PLATFORM_ADMIN_PASSWORD': platform_password, 'PLATFORM_ADMIN_EMAIL': batch + '@example.test',
            'NACOS_DISCOVERY_ENABLED': 'false', 'NACOS_CONFIG_ENABLED': 'false', 'SKILLFORGE_DEBUG': 'true',
            'INSTITUTION_DB_POOL_SIZE': '32', 'INSTITUTION_DB_POOL_MIN_IDLE': '2',
            'ARTIFACT_ROOT': str(root / 'artifacts'), 'WORKSPACE_ROOT': str(root / 'workspace'), 'FILES_ROOT': str(root / 'files'),
            'WF_LIVE_BATCH': batch, 'WF_LIVE_TENANT_SLUG': batch, 'WF_LIVE_TENANT_USER': 'workflow-admin',
            'WF_LIVE_TENANT_PASSWORD': tenant_password,
        }
        (root / 'private.env').write_text(''.join(f'{key}={value}\n' for key, value in sorted(env.items())), encoding='utf-8')
        state.update({'status': 'DEPENDENCIES_READY', 'database': 'workflow_live',
                      'private_env': str(root / 'private.env'), 'flyway_owner': 'skillforge',
                      'institution_role': 'skillforge_app', 'platform_role': 'skillforge_platform'})
        save(root / 'environment.json', state)
        print(json.dumps({'status': state['status'], 'root': str(root), 'batch': batch,
                          'postgres_port': state['containers']['postgres']['port'],
                          'redis_port': state['containers']['redis']['port']}, ensure_ascii=False))
    except Exception as failure:
        state.update({'status': 'PREPARE_FAILED_OR_UNKNOWN', 'failure_type': type(failure).__name__})
        save(root / 'environment.json', state)
        raise RuntimeError('private_environment_preparation_failed; inspect owned manifest, do not blindly rerun') from None


def service_process(service, root: Path, stop=False):
    """先检查真实 PID 与完整 -jar 参数；不依据端口或进程名批量终止。"""
    pid = int(service['pid'])
    jar = Path(service['jar']).resolve()
    if not jar.is_relative_to(root):
        raise RuntimeError('service_jar_outside_owned_root')
    if os.name == 'nt':
        script = r'''
$candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$env:WF_CLEAN_PID"
if (-not $candidate) { exit 0 }
$pattern = '(?i)(?:^|\s)-jar\s+"?' + [regex]::Escape($env:WF_CLEAN_JAR) + '"?(?:\s|$)'
if ($candidate.Name -ne 'java.exe' -or $candidate.CommandLine -notmatch $pattern) { throw 'owned_service_process_mismatch' }
if ($env:WF_CLEAN_STOP -eq 'true') {
    $ownedProcess = Get-Process -Id ([int]$env:WF_CLEAN_PID) -ErrorAction Stop
    Stop-Process -InputObject $ownedProcess -ErrorAction Stop
    if (Get-Process -Id ([int]$env:WF_CLEAN_PID) -ErrorAction SilentlyContinue) {
        Wait-Process -Id ([int]$env:WF_CLEAN_PID) -Timeout 20 -ErrorAction SilentlyContinue
    }
    if (Get-Process -Id ([int]$env:WF_CLEAN_PID) -ErrorAction SilentlyContinue) { throw 'owned_service_not_stopped' }
}
exit 0
'''
        environment = {**os.environ, 'WF_CLEAN_PID': str(pid), 'WF_CLEAN_JAR': str(jar),
                       'WF_CLEAN_STOP': 'true' if stop else 'false'}
        result = subprocess.run(['powershell', '-NoProfile', '-Command', script], env=environment,
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError('owned_service_process_mismatch_or_stop_failed')
    else:
        cmdline = Path(f'/proc/{pid}/cmdline')
        if not cmdline.exists():
            return
        arguments = cmdline.read_bytes().decode().split('\0')
        if '-jar' not in arguments or Path(arguments[arguments.index('-jar') + 1]).resolve() != jar:
            raise RuntimeError('owned_service_process_mismatch')
        if stop:
            os.kill(pid, signal.SIGTERM)
            deadline = time.monotonic() + 20
            while cmdline.exists() and time.monotonic() < deadline:
                time.sleep(.2)
            if cmdline.exists():
                raise RuntimeError('owned_service_not_stopped')


def cleanup(root: Path):
    root = root.resolve()
    if not root.is_relative_to((REPO / '.runtime-logs').resolve()):
        raise RuntimeError('owned_runtime_directory_required')
    state = json.loads((root / 'environment.json').read_text(encoding='utf-8'))
    batch = state['batch']
    if not re.fullmatch(r'sf-wf-live-[0-9a-f]{10}', batch):
        raise RuntimeError('owned_batch_identity_required')
    cleanup_path = root / 'cleanup.json'
    receipt = json.loads(cleanup_path.read_text(encoding='utf-8')) if cleanup_path.exists() else {'batch': batch, 'containers_removed': [], 'services_stopped': []}
    if receipt['batch'] != batch:
        raise RuntimeError('cleanup_receipt_owner_mismatch')
    # 整批先预检，任一归属不符时不回收其他资源；不读取或输出容器环境。
    for resource in state['containers'].values():
        if resource['id'] in receipt['containers_removed']:
            continue
        actual = json.loads(docker('inspect', '--format',
            '{"id":{{json .Id}},"owner":{{json (index .Config.Labels "skillforge.workflow-live")}},"name":{{json .Name}}}', resource['id']))
        if actual != {'id': resource['id'], 'owner': batch, 'name': '/' + resource['name']}:
            raise RuntimeError('owned_container_identity_mismatch')
    for service in state.get('services', {}).values():
        if service['pid'] not in receipt['services_stopped']:
            service_process(service, root)
    sanitized = root / 'sanitized-logs'
    sanitized.mkdir(exist_ok=True)
    environment = load_env(root / 'private.env')
    secrets_to_remove = [value for key, value in environment.items() if len(value) >= 8
                         and any(part in key.lower() for part in ('password', 'secret', 'api_key', 'model_key'))]
    for log in root.glob('*.log'):
        content = log.read_text(encoding='utf-8', errors='replace')
        for secret in secrets_to_remove:
            content = content.replace(secret, '[REDACTED]')
        (sanitized / log.name).write_text(content, encoding='utf-8')
    for service in reversed(list(state.get('services', {}).values())):
        if service['pid'] not in receipt['services_stopped']:
            service_process(service, root, stop=True)
            receipt['services_stopped'].append(service['pid'])
            save(cleanup_path, receipt)
    for resource in reversed(list(state['containers'].values())):
        if resource['id'] not in receipt['containers_removed']:
            docker('rm', '-f', '-v', resource['id'])
            receipt['containers_removed'].append(resource['id'])
            save(cleanup_path, receipt)
    receipt['status'] = 'CLEANED_OWNED_RESOURCES_LOGS_PRESERVED'
    save(cleanup_path, receipt)
    print(json.dumps({'status': receipt['status'], 'root': str(root)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'cleanup'])
    parser.add_argument('--resume', type=Path, help='仅恢复 manifest 中的同批失败初始化，不新建容器')
    parser.add_argument('--root', type=Path, help='cleanup 只回收该独占 manifest 的服务与容器，保留本地日志')
    arguments = parser.parse_args()
    if arguments.command == 'cleanup':
        if not arguments.root:
            parser.error('cleanup requires --root')
        cleanup(arguments.root)
    else:
        prepare(arguments.resume)
