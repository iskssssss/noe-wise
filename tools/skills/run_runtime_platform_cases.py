"""真实冻结LinuxPython镜像执行Windows无法代表的symlink与LibreOffice五例。"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

from python_runtime_acceptance import DRIVER, ROOT

CASES = ('test_configuration_skill.ConfigurationSkillTest.test_schema_mount_cannot_follow_symlink_outside_input',
         'test_document_skill.DocumentSkillTest.test_rejects_symlink_escape_when_platform_allows_symlinks',
         'test_spreadsheet_skill.SpreadsheetSkillTest.test_x02_real_cross_sheet_recalculation_and_chart_cache',
         'test_spreadsheet_skill.SpreadsheetSkillTest.test_real_recalculation_preserves_equals_text_and_updates_original',
         'test_spreadsheet_skill.SpreadsheetSkillTest.test_real_error_formula_is_rejected_without_candidate')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', args.image]))[0]
    if image['Config'].get('User') != '65532:65532':
        raise RuntimeError('FROZEN_NONROOT_RUNTIME_REQUIRED')
    args.evidence.mkdir(parents=True, exist_ok=False)
    scope = {'image_id': image['Id'], 'user': '65532:65532', 'network': 'none', 'read_only': True,
             'memory': '1g', 'cpus': 2, 'cases': CASES, 'paid_model_calls': 0, 'source_sha256': {}}
    mounts = []
    for package in ('configuration', 'document', 'spreadsheet'):
        module = ROOT / 'tools/skills' / ('test_' + package + '_skill.py')
        source = ROOT / 'official-skills' / package
        for local, target in ((module, '/repo/tools/skills/' + module.name),
                              (source, '/repo/official-skills/' + package)):
            mounts += ['--mount', f'type=bind,source={local},target={target},readonly']
        scope['source_sha256'][str(module.relative_to(ROOT))] = hashlib.sha256(module.read_bytes()).hexdigest()
        for file in source.rglob('*'):
            if file.is_file() and '__pycache__' not in file.parts:
                scope['source_sha256'][str(file.relative_to(ROOT))] = hashlib.sha256(file.read_bytes()).hexdigest()
    name = 'full-test-platform-cases-' + uuid.uuid4().hex
    scope['container_name'] = name
    (args.evidence / 'scope.json').write_text(json.dumps(scope, indent=2), encoding='utf-8')
    driver = DRIVER.replace('loadTestsFromName(sys.argv[1])', 'loadTestsFromNames(sys.argv[1:])')
    command = ['docker', 'run', '--rm', '--name', name, '--network', 'none', '--read-only', '--memory', '1g',
        '--cpus', '2', '--pids-limit', '256', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--tmpfs', '/tmp:rw,nosuid,nodev,size=256m,mode=1777', *mounts, '--entrypoint', 'python', image['Id'],
        '-B', '-c', driver, *CASES]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=360)
        (args.evidence / 'stdout.txt').write_text(completed.stdout, encoding='utf-8')
        (args.evidence / 'stderr.txt').write_text(completed.stderr, encoding='utf-8')
        records = [line.removeprefix('RUNTIME_TEST_REPORT=') for line in completed.stdout.splitlines()
                   if line.startswith('RUNTIME_TEST_REPORT=')]
        if completed.returncode or len(records) != 1:
            raise RuntimeError('ACTUAL_PLATFORM_CASE_REPORT_MISSING')
        report = json.loads(records[0])
        if report['tests_run'] != 5 or set(report['cases']) != set(CASES):
            raise RuntimeError('ACTUAL_PLATFORM_CASES_NOT_ALL_EXECUTED')
        (args.evidence / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        failed = [key for key, result in report['cases'].items() if result['status'] != 'PASS']
        print(json.dumps({'status': 'FAIL' if failed else 'PASS', 'tests': report['tests_run'], 'failed': failed,
                          'evidence': str(args.evidence.resolve()), 'paid_model_calls': 0}))
        if failed:
            raise SystemExit(1)
    finally:
        subprocess.run(['docker', 'rm', '--force', name], capture_output=True, timeout=20)


if __name__ == '__main__':
    main()
