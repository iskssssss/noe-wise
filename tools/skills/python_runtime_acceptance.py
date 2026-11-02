"""把Linux镜像能力测试委派到冻结的隔离容器，回传每个真实unittest结果。"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
_CACHE = {}

DRIVER = r'''import json,sys,unittest
sys.path.insert(0,'/repo/tools/skills')
class Results(unittest.TestResult):
    def __init__(self): super().__init__();self.observed={}
    def addSuccess(self,test):super().addSuccess(test);self.observed[test.id()]={'status':'PASS'}
    def addError(self,test,error):super().addError(test,error);self.observed[test.id()]={'status':'ERROR','detail':self._exc_info_to_string(error,test)}
    def addFailure(self,test,error):super().addFailure(test,error);self.observed[test.id()]={'status':'FAIL','detail':self._exc_info_to_string(error,test)}
    def addSkip(self,test,reason):super().addSkip(test,reason);self.observed[test.id()]={'status':'SKIP','detail':reason}
    def addSubTest(self,test,subtest,error):
        super().addSubTest(test,subtest,error)
        if error:self.observed[test.id()]={'status':'FAIL','detail':self._exc_info_to_string(error,test)}
suite=unittest.defaultTestLoader.loadTestsFromName(sys.argv[1]);result=Results();suite.run(result)
print('RUNTIME_TEST_REPORT='+json.dumps({'tests_run':result.testsRun,'cases':result.observed},ensure_ascii=False))
'''


def _run(module, class_name):
    source = ROOT / 'tools/skills' / (module + '.py')
    package = ROOT / 'official-skills/document-read'
    configured = os.environ.get('SKILL_DOCUMENT_READ_RUNTIME_IMAGE', 'skillforge-sandbox-python:3.12')
    image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', configured]))[0]
    if image['Config'].get('User') != '65532:65532':
        raise RuntimeError('DOCUMENT_READER_RUNTIME_MUST_BE_NON_ROOT')
    files = {str(path.relative_to(package)): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in package.rglob('*') if path.is_file() and '__pycache__' not in path.parts}
    fingerprint = hashlib.sha256(json.dumps({'module': hashlib.sha256(source.read_bytes()).hexdigest(),
        'package': files, 'image_id': image['Id']}, sort_keys=True).encode()).hexdigest()
    key = (module, class_name, fingerprint)
    if key in _CACHE:
        return _CACHE[key]
    identity = uuid.uuid4().hex
    evidence_root = Path(os.environ.get('SKILL_DOCUMENT_READ_RUNTIME_EVIDENCE', str(ROOT / '.agent-work-state/document-read-runtime-acceptance'))).resolve()
    evidence = evidence_root / identity
    evidence.mkdir(parents=True, exist_ok=False)
    name = 'full-test-document-reader-' + identity
    command = ['docker', 'run', '--rm', '--name', name, '--network', 'none', '--read-only', '--memory', '1g',
               '--cpus', '2', '--pids-limit', '256', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--tmpfs', '/tmp:rw,nosuid,nodev,size=256m,mode=1777',
               '--mount', f'type=bind,source={source},target=/repo/tools/skills/{module}.py,readonly',
               '--mount', f'type=bind,source={Path(__file__).resolve()},target=/repo/tools/skills/python_runtime_acceptance.py,readonly',
               '--mount', f'type=bind,source={package},target=/repo/official-skills/document-read,readonly',
               '--entrypoint', 'python', image['Id'], '-B', '-c', DRIVER, module + '.' + class_name]
    scope = {'schema_version': 1, 'container_name': name, 'image_id': image['Id'], 'fingerprint': fingerprint,
             'source_module': str(source), 'source_package_sha256': files, 'user': '65532:65532',
             'memory': '1g', 'network': 'none', 'read_only': True, 'paid_model_calls': 0}
    (evidence / 'scope.json').write_text(json.dumps(scope, indent=2), encoding='utf-8')
    try:
        completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=360)
        (evidence / 'stdout.txt').write_text(completed.stdout, encoding='utf-8')
        (evidence / 'stderr.txt').write_text(completed.stderr, encoding='utf-8')
        records = [line.removeprefix('RUNTIME_TEST_REPORT=') for line in completed.stdout.splitlines()
                   if line.startswith('RUNTIME_TEST_REPORT=')]
        if completed.returncode != 0 or len(records) != 1:
            raise RuntimeError('ISOLATED_DOCUMENT_READER_INCOMPLETE_REPORT_SEE_' + str(evidence))
        report = json.loads(records[0])
        if report.get('tests_run', 0) <= 0 or len(report.get('cases', {})) != report['tests_run']:
            raise RuntimeError('ISOLATED_DOCUMENT_READER_MISSING_TEST_RESULTS')
        report['scope'] = scope
        (evidence / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        _CACHE[key] = report
        return report
    finally:
        subprocess.run(['docker', 'rm', '--force', name], capture_output=True, timeout=20)


def delegate_runtime_test(test, result=None):
    """Windows不能代表Linux Docling镜像：执行完整真实类，再按原test ID传回结果。"""
    result = result or test.defaultTestResult()
    result.startTest(test)
    try:
        report = _run(test.__class__.__module__, test.__class__.__name__)
        observed = report['cases'].get(test.id())
        if not observed:
            raise RuntimeError('NO_ACTUAL_RUNTIME_RESULT_FOR_' + test.id())
        if observed['status'] == 'PASS':
            result.addSuccess(test)
        elif observed['status'] == 'SKIP':
            result.addSkip(test, observed['detail'])
        else:
            raise AssertionError(observed['status'] + ' in actual isolated runtime:\n' + observed.get('detail', ''))
    except Exception:
        import sys
        result.addFailure(test, sys.exc_info())
    finally:
        result.stopTest(test)
    return result
