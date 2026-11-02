"""在已构建的真实 Node 镜像内运行网页验收入口，保留只读、断网和非 root 约束。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'official-skills'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='skillforge-sandbox-node:24')
    parser.add_argument('--output', type=Path, default=ROOT / '.agent-work-state/skill-strengthening/web-testing/container')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    image = json.loads(subprocess.run(['docker', 'image', 'inspect', args.image],
                                     capture_output=True, text=True, check=True).stdout)[0]
    if image['Config']['User'] != '65532:65532':
        raise AssertionError('runtime image must declare non-root user 65532:65532')
    html = json.loads((PACKAGE / 'web-app/examples/site.json').read_text(encoding='utf-8'))['html']
    steps = [
        {'op': 'assert_visible_count', 'selector': 'tbody tr', 'expected': 2},
        {'op': 'fill', 'selector': '#filter', 'value': '备份'},
        {'op': 'assert_visible_count', 'selector': 'tbody tr', 'expected': 1},
        {'op': 'assert_text', 'selector': '#count', 'expected': '1 项需求'},
        {'op': 'fill', 'selector': '#filter', 'value': ''},
        {'op': 'assert_visible_count', 'selector': 'tbody tr', 'expected': 2},
        {'op': 'focus', 'selector': '#filter'},
        {'op': 'assert_focused', 'selector': '#filter'},
        {'op': 'press', 'selector': '#filter', 'key': 'Home'},
    ]
    summary = []
    for case, plan, expected_exit, expected_status in [
        ('generated-web-app', {'source_path': '/input/data/index.html', 'ready_selector': 'tbody tr', 'steps': steps}, 0, 'passed'),
        ('failed-business-check', {'source_path': '/input/data/index.html', 'steps': [
            {'op': 'assert_visible_count', 'selector': 'tbody tr', 'expected': 3}]}, 0, 'failed'),
        ('ready-failed-preserves-files', {'source_path': '/input/data/index.html', 'ready_selector': '#missing-ready', 'steps': []}, 0, 'failed'),
        ('invalid-input-path', {'source_path': '/input/data/../index.html', 'steps': []}, 1, None),
    ]:
        private = output / case
        data = private / 'data'
        artifacts = private / 'artifacts'
        files = private / 'files'
        work = private / 'work'
        for directory in (data, artifacts, files, work):
            directory.mkdir(parents=True, exist_ok=True)
        (data / 'index.html').write_text(html, encoding='utf-8')
        (data / 'webapp-check.json').write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
        name = 'skillforge-web-check-' + uuid.uuid4().hex
        command = ['docker', 'run', '--rm', '--name', name, '--network', 'none', '--read-only',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '1g',
                   '--cpus', '2', '--pids-limit', '256', '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777',
                   '--env', 'HOME=/workspace/work/home']
        for source, target, readonly in [(data, '/input/data', True), (artifacts, '/input/artifacts', True),
                                        (files, '/workspace/output', False), (work, '/workspace/work', False),
                                        (PACKAGE / 'webapp-testing/scripts/check_webapp.mjs',
                                         '/opt/skillforge/playwright/check_webapp.mjs', True)]:
            command += ['--mount', f'type=bind,source={source},target={target}' + (',readonly' if readonly else '')]
        command += [args.image, 'execute', '/opt/skillforge/playwright/check_webapp.mjs']
        try:
            result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=110)
        finally:
            # 正常 --rm 已静止；超时也只清理由本次随机名字创建的容器。
            subprocess.run(['docker', 'rm', '--force', name], capture_output=True, timeout=15)
        (private / 'stdout.txt').write_text(result.stdout, encoding='utf-8')
        (private / 'stderr.txt').write_text(result.stderr, encoding='utf-8')
        if result.returncode != expected_exit:
            raise AssertionError(f'{case}: expected exit {expected_exit}, actual {result.returncode}; see private stderr')
        observed = None
        if expected_status:
            report = json.loads((files / 'check-report.json').read_text(encoding='utf-8'))
            if report['status'] != expected_status or report['checks_passed'] != (expected_status == 'passed'):
                raise AssertionError(f'{case}: unexpected check status')
            if report['visual_review'] != 'not_performed' or len(report['screenshots']) != 2:
                raise AssertionError(f'{case}: screenshot or visual review contract differs')
            if report['source_sha256'] != hashlib.sha256(html.encode()).hexdigest():
                raise AssertionError(f'{case}: source digest differs')
            observed = report['status']
        summary.append({'case': case, 'exit_code': result.returncode, 'check_status': observed, 'expected': True})
        if list(work.glob('skillforge-chromium-*')):
            raise AssertionError(f'{case}: browser temporary directory was not cleaned')
    # Linux 能实际创建符号链接，补齐 Windows 主机未具备权限的输入根逃逸负例。
    symlink_probe = """import * as fs from 'node:fs/promises';
import { resolveInput } from '/opt/skillforge/playwright/check_webapp.mjs';
await fs.mkdir('/workspace/work/input',{recursive:true});
await fs.symlink('/etc/passwd','/workspace/work/input/escape.html');
try { await resolveInput('/workspace/work/input/escape.html',['/workspace/work/input']);
  throw new Error('symlink escape was accepted');
} catch(error) { if(!error.message.includes('超出')) throw error;
  console.log(JSON.stringify({case:'linux-symlink-escape',status:'rejected'})); }
"""
    probe = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                            '--security-opt', 'no-new-privileges', '--tmpfs',
                            '/workspace/work:rw,nosuid,nodev,size=8m,uid=65532,gid=65532,mode=700',
                            '--mount', f'type=bind,source={PACKAGE / "webapp-testing/scripts/check_webapp.mjs"},target=/opt/skillforge/playwright/check_webapp.mjs,readonly',
                            '--entrypoint', 'node', args.image, '--input-type=module', '-e', symlink_probe],
                           capture_output=True, text=True, encoding='utf-8', timeout=20)
    if probe.returncode != 0:
        raise AssertionError('Linux symlink escape rejection failed: ' + probe.stderr)
    summary.append(json.loads(probe.stdout))
    report = {'image_id': image['Id'], 'network': 'none', 'read_only_root': True, 'capabilities': [],
              'user': image['Config']['User'], 'memory': '1g', 'tmpfs': '64m,noexec', 'browser_temporary_cleaned': True,
              'visual_review': 'not_performed', 'cases': summary}
    (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
