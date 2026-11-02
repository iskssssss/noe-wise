"""创建本批独立Browser服务、网络与数据卷；仅loopback WS，不操作已有设备。"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import urllib.request


def docker(*args, environment=None):
    result = subprocess.run(['docker', *args], capture_output=True, text=True, encoding='utf-8', timeout=90, env=environment)
    if result.returncode:
        raise RuntimeError('ISOLATED_BROWSER_DOCKER_' + args[0].upper() + '_FAILED')
    return result.stdout.strip()


def prepare(output, batch, image_name):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,46}', batch):
        raise ValueError('EXPLICIT_ISOLATED_BROWSER_BATCH_REQUIRED')
    prefix = batch + '-browser'
    output.mkdir(parents=True, exist_ok=False)
    if docker('ps', '-a', '--filter', 'label=skillforge.test.batch=' + batch, '--filter', 'label=skillforge.test.kind=browser', '--format', '{{.ID}}'):
        raise RuntimeError('EXISTING_BROWSER_BATCH_QUERY_ORIGINAL_RESOURCES')
    image = docker('image', 'inspect', '--format', '{{.Id}}', image_name)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    token = secrets.token_urlsafe(48)
    scope = {'batch_id': batch, 'prefix': prefix, 'container': prefix + '-service', 'image_id': image,
             'network': prefix + '-control', 'volume': prefix + '-data', 'endpoint': 'http://127.0.0.1:' + str(port),
             'public_base': 'ws://127.0.0.1:' + str(port), 'official_cli_version_expected': '0.3.2',
             'official_commit_contract': '3f10983c09fcd443c7420830ab984c9d409954bf',
             'cli_source_provenance': 'repository_Dockerfile_pins_commit_existing_image_requires_recorded_build_evidence',
             'paid_model_calls': 0}
    private = dict(scope, token=token)
    (output / 'service.private.json').write_text(json.dumps(private, indent=2) + '\n', encoding='utf-8')
    (output / 'scope.json').write_text(json.dumps(scope, indent=2) + '\n', encoding='utf-8')
    docker('network', 'create', '--label', 'skillforge.test.batch=' + batch, scope['network'])
    docker('volume', 'create', '--label', 'skillforge.test.batch=' + batch, scope['volume'])
    environment = os.environ | {'BROWSER_SERVICE_TOKEN': token}
    container_id = docker('run', '-d', '--name', scope['container'], '--label', 'skillforge.test.batch=' + batch,
        '--label', 'skillforge.test.kind=browser', '--network', scope['network'], '--memory', '2g', '--memory-swap', '2g', '--cpus', '2',
        '--pids-limit', '256', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--env', 'BROWSER_SERVICE_TOKEN', '--env', 'BROWSER_PUBLIC_BASE_URL=' + scope['public_base'],
        '--env', 'BROWSER_DATA_ROOT=/var/lib/bsk', '--mount', 'type=volume,source=' + scope['volume'] + ',target=/var/lib/bsk',
        '--publish', '127.0.0.1:' + str(port) + ':8300', image, environment=environment)
    private['container_id'] = container_id; scope['container_id'] = container_id
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        try:
            with opener.open(scope['endpoint'] + '/actuator/health', timeout=3) as response:
                if json.load(response).get('status') == 'UP':
                    scope['cli_version_observed'] = docker('exec', scope['container'], '/opt/bsk/bsk', '--version')
                    scope['cli_sha256_observed'] = docker('exec', scope['container'], 'sha256sum', '/opt/bsk/bsk').split()[0]
                    private.update(scope)
                    (output / 'service.private.json').write_text(json.dumps(private, indent=2) + '\n', encoding='utf-8')
                    (output / 'scope.json').write_text(json.dumps(scope, indent=2) + '\n', encoding='utf-8')
                    return scope
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError('ISOLATED_BROWSER_NOT_READY_KEEP_OWNED_RESOURCES_FOR_DIAGNOSTICS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--service-image', required=True)
    args = parser.parse_args()
    scope = prepare(args.output.resolve(), args.batch_id, args.service_image)
    print(json.dumps({'status': 'READY', 'endpoint': scope['endpoint'], 'container': scope['container'],
                      'private_config': str(args.output.resolve() / 'service.private.json')}))
