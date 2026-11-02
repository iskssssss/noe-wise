"""创建本批独立Sandbox、proxy、CA与卷；不接管或重启已有服务。"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def docker(*args, environment=None):
    """不回显Docker stderr/env；错误类型由私有证据定位。"""
    result = subprocess.run(['docker', *args], capture_output=True, text=True, encoding='utf-8', timeout=240,
                            env=environment)
    if result.returncode:
        raise RuntimeError('ISOLATED_DOCKER_' + args[0].upper() + '_FAILED')
    return result.stdout


def prepare(output: Path, batch: str, service_image: str) -> dict:
    """在全新证据目录生成精确资源清单和私有凭据，不执行启动。"""
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,46}', batch):
        raise ValueError('LOWERCASE_ISOLATED_BATCH_REQUIRED')
    prefix = batch + '-sandbox'
    output.mkdir(parents=True, exist_ok=False)
    images = {}
    for key, image in {'service': service_image, 'python': 'skillforge-sandbox-python:3.12', 'node': 'skillforge-sandbox-node:24',
                       'proxy': 'skillforge-sandbox-egress-proxy:11.0.2', 'ca': 'skillforge-sandbox-ca-init:1'}.items():
        images[key] = docker('image', 'inspect', '--format', '{{.Id}}', image).strip()
    token = secrets.token_urlsafe(48)
    volumes = {key: {'name': prefix + '-' + key} for key in ('tasks', 'projects', 'ca')}
    services = {
        'ca-init': {'image': images['ca'], 'container_name': prefix + '-ca-init', 'volumes': ['ca:/ca'], 'restart': 'no'},
        'proxy': {'image': images['proxy'], 'container_name': prefix + '-proxy', 'depends_on': {'ca-init': {'condition': 'service_completed_successfully'}},
                  'volumes': ['ca:/ca:ro'], 'networks': {'task': {'aliases': [prefix + '-egress-proxy']}, 'uplink': {}},
                  'read_only': True, 'tmpfs': ['/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777'], 'cap_drop': ['ALL'],
                  'security_opt': ['no-new-privileges:true'], 'pids_limit': 128, 'mem_limit': '256m', 'cpus': 0.5,
                  'healthcheck': {'test': ['CMD', 'python', '-c', "import socket; s=socket.create_connection(('127.0.0.1',8080),2); s.close()"],
                                  'interval': '3s', 'timeout': '3s', 'retries': 30}},
        'service': {'image': images['service'], 'container_name': prefix + '-service', 'mem_limit': '1g',
                    'depends_on': {'proxy': {'condition': 'service_healthy'}},
                    'environment': {'SANDBOX_SERVICE_TOKEN': '${FULL_TEST_SANDBOX_TOKEN:?}', 'SANDBOX_TASK_NETWORK': prefix + '-task',
                                    'SANDBOX_TASK_VOLUME': prefix + '-tasks', 'SANDBOX_CA_VOLUME': prefix + '-ca',
                                    'SANDBOX_PROXY_URL': 'http://' + prefix + '-egress-proxy:8080', 'SANDBOX_PROXY_CONTAINER': prefix + '-proxy',
                                    'SANDBOX_PYTHON_IMAGE': images['python'], 'SANDBOX_NODE_IMAGE': images['node'],
                                    'SANDBOX_WORKFLOW_PYTHON_IMAGE': images['python'], 'SANDBOX_WORKFLOW_NODE_IMAGE': images['node'],
                                    'SANDBOX_PROJECT_ROOT': '/var/lib/skillforge-projects', 'SANDBOX_PROJECT_VOLUME': prefix + '-projects'},
                    'volumes': ['/var/run/docker.sock:/var/run/docker.sock:ro', 'tasks:/var/lib/skillforge-sandbox', 'projects:/var/lib/skillforge-projects'],
                    'networks': ['control'], 'ports': ['127.0.0.1:0:8200'], 'restart': 'unless-stopped'}}
    networks = {name: {'name': prefix + '-' + name} for name in ('control', 'uplink')}
    networks['task'] = {'name': prefix + '-task', 'internal': True, 'enable_ipv6': False,
                        'driver_opts': {'com.docker.network.bridge.gateway_mode_ipv4': 'isolated'}}
    compose = {'name': prefix, 'services': services, 'volumes': volumes, 'networks': networks}
    (output / 'compose.json').write_text(json.dumps(compose, indent=2) + '\n', encoding='utf-8')
    private = {'batch_id': batch, 'prefix': prefix, 'token': token, 'endpoint': None, 'container': prefix + '-service',
               'owner': hashlib.sha256((prefix + '-tasks').encode()).hexdigest(), 'images': images}
    (output / 'service.private.json').write_text(json.dumps(private, indent=2) + '\n', encoding='utf-8')
    (output / 'scope.json').write_text(json.dumps({key: value for key, value in private.items() if key != 'token'}, indent=2) + '\n', encoding='utf-8')
    return private


def start(output: Path, private: dict):
    """拒绝已有同名容器；新服务监听后读取随机端口并等待实际健康状态。"""
    if docker('ps', '-a', '--filter', 'label=com.docker.compose.project=' + private['prefix'], '--format', '{{.ID}}').strip():
        raise RuntimeError('EXISTING_BATCH_REQUIRES_EXPLICIT_RECEIPT_QUERY')
    environment = os.environ | {'FULL_TEST_SANDBOX_TOKEN': private['token']}
    docker('compose', '-p', private['prefix'], '-f', str(output / 'compose.json'), 'up', '-d', environment=environment)
    deadline = time.monotonic() + 120
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.monotonic() < deadline:
        port = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', private['container'])).get('8200/tcp')
        if port and len(port) == 1 and port[0]['HostIp'] == '127.0.0.1':
            private['endpoint'] = 'http://127.0.0.1:' + port[0]['HostPort']
            try:
                with opener.open(private['endpoint'] + '/actuator/health', timeout=3) as response:
                    if json.load(response).get('status') == 'UP':
                        private['container_id'] = docker('inspect', '--format', '{{.Id}}', private['container']).strip()
                        (output / 'service.private.json').write_text(json.dumps(private, indent=2) + '\n', encoding='utf-8')
                        (output / 'scope.json').write_text(json.dumps({key: value for key, value in private.items() if key != 'token'}, indent=2) + '\n', encoding='utf-8')
                        return
            except (OSError, ValueError):
                pass
        time.sleep(0.5)
    raise RuntimeError('ISOLATED_SANDBOX_READINESS_TIMEOUT_KEEP_OWNED_RESOURCES_FOR_DIAGNOSTICS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--service-image', default='skillforge-sandbox-service:0.2.0')
    parser.add_argument('--start', action='store_true')
    args = parser.parse_args()
    private = prepare(args.output.resolve(), args.batch_id, args.service_image)
    if args.start:
        start(args.output.resolve(), private)
    print(json.dumps({'status': 'ready' if args.start else 'prepared', 'endpoint': private['endpoint'], 'container': private['container'],
                      'scope': str(args.output.resolve() / 'scope.json'), 'private_config': str(args.output.resolve() / 'service.private.json')}))
