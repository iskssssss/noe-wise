"""零模型调用的隔离Sandbox复杂文件验收；未知提交保留原task，只允许查询。"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

from complex_fixture import fixture

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'skills'))
from sandbox_acceptance_support import ACTIVE_STATES, NoRedirect, admission_failure, configuration


PYTHON_CODE = r'''import csv, hashlib, json
from collections import Counter
from pathlib import Path
source=Path('/input/data'); output=Path('/workspace/output')
records=json.loads((source/'data.json').read_text())['records']
assert len(records)==72 and len({r['record_id'] for r in records})==72
for name, delimiter in [('sales-72.csv',','),('sales-72.tsv','\t')]:
    with (source/name).open(newline='',encoding='utf-8') as stream:
        parsed=list(csv.DictReader(stream,delimiter=delimiter))
    assert len(parsed)==72
    for actual, expected in zip(parsed,records):
        assert int(actual.pop('amount_cents'))==expected['amount_cents']
        assert actual=={key:'' if value is None else str(value) for key,value in expected.items() if key!='amount_cents'}
assert len((source/'records.txt').read_text().splitlines())==72
assert (source/'brief.md').read_text().count('|sale-')==72
assert (source/'index.html').read_text().count('<tr data-id=')==72
assert sum(r['owner'] is None for r in records)==8
assert Counter(r['status'] for r in records)=={'待审批':18,'已确认':54}
totals={f'部门{i}':sum(r['amount_cents'] for r in records if r['department']==f'部门{i}') for i in range(1,7)}
assert sum(totals.values())==903600
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in
        ['sales-72.csv','sales-72.tsv','data.json','brief.md','records.txt','index.html']}
result={'rows':72,'total_cents':sum(totals.values()),'department_totals_cents':totals,
        'pending_rows':18,'confirmed_rows':54,'null_owners':8,'input_sha256':hashes}
(output/'reports').mkdir(parents=True,exist_ok=True);(output/'site').mkdir(parents=True,exist_ok=True)
(output/'reports/totals.json').write_text(json.dumps(result,ensure_ascii=False,sort_keys=True)+'\n',encoding='utf-8')
(output/'reports/summary.md').write_text('# 部门月报\n\n72条记录，金额903600分；待审批18条，已确认54条，负责人为空8条。\n',encoding='utf-8')
(output/'site/index.html').write_bytes((source/'index.html').read_bytes())
(output/'site/data.json').write_bytes((source/'data.json').read_bytes())
(output/'private-check.txt').write_text('UND ECLARED PRIVATE CONTENT MUST NOT BE DELIVERED')
'''

NODE_CODE = r'''import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const source='/input/data/',output='/workspace/output/';
const document=JSON.parse(await readFile(source+'data.json','utf8')),rows=document.records;
assert.equal(rows.length,72);assert.equal(new Set(rows.map(r=>r.record_id)).size,72);
assert.equal(document.references.csv,'sales-72.csv');assert.equal(document.references.brief,'brief.md');
const totals={};for(let department=1;department<=6;department++){
 const selected=rows.filter(r=>r.department===`部门${department}`);
 assert.equal(selected.length,12);assert.equal(new Set(selected.map(r=>r.month)).size,12);
 totals[`部门${department}`]=selected.reduce((sum,r)=>sum+r.amount_cents,0);
}
for(let index=0;index<72;index++)assert.equal(rows[index].amount_cents,12050+(index%9)*125);
assert.equal(rows.filter(r=>r.owner===null).length,8);assert.equal(rows.filter(r=>r.status==='待审批').length,18);
assert.equal(rows.filter(r=>r.status==='已确认').length,54);
assert.ok(rows.some(r=>r.note.includes('\n')));assert.ok(rows.some(r=>r.note==='=待确认'));
const hashes={};for(const name of ['sales-72.csv','sales-72.tsv','data.json','brief.md','records.txt','index.html']){
 const content=await readFile(source+name);hashes[name]=createHash('sha256').update(content).digest('hex');
}
assert.equal((await readFile(source+'index.html','utf8')).split('<tr data-id=').length-1,72);
const result={rows:72,total_cents:Object.values(totals).reduce((a,b)=>a+b,0),department_totals_cents:totals,
 pending_rows:18,confirmed_rows:54,null_owners:8,input_sha256:hashes};assert.equal(result.total_cents,903600);
await mkdir(output+'reports',{recursive:true});await mkdir(output+'site',{recursive:true});
await writeFile(output+'reports/totals.json',JSON.stringify(result)+'\n');
await writeFile(output+'reports/summary.md','# 部门月报\n\n72条记录，金额903600分；待审批18条，已确认54条，负责人为空8条。\n');
await writeFile(output+'site/index.html',await readFile(source+'index.html'));
await writeFile(output+'site/data.json',await readFile(source+'data.json'));
await writeFile(output+'private-check.txt','UNDECLARED PRIVATE CONTENT MUST NOT BE DELIVERED');
'''

OUTPUTS = ['reports/totals.json', 'reports/summary.md', 'site/index.html', 'site/data.json']


def save(path: Path, value: dict):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def execute(service: Path, batch: str, output: Path, case: str, wait: int = 45) -> dict:
    endpoint, token = configuration(service, batch)
    inputs, oracle = fixture()
    inputs = {'data/' + name: content for name, content in inputs.items()}
    code = NODE_CODE if case == 'node' else PYTHON_CODE
    metadata = [{'path': name, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
                for name, content in sorted(inputs.items())]
    fingerprint = hashlib.sha256(json.dumps({'metadata': metadata, 'code': code, 'case': case, 'inline_inputs': [],
                                            'outputs': OUTPUTS}, sort_keys=True).encode()).hexdigest()
    output.mkdir(parents=True, exist_ok=True)
    receipt_file = output / 'receipt.json'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(method, route, body=None):
        control = urllib.request.Request(endpoint + '/internal/executions' + route, data=body, method=method,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/zip' if body else 'application/json'})
        return opener.open(control, timeout=45)

    if receipt_file.exists():
        receipt = json.loads(receipt_file.read_text(encoding='utf-8'))
        if any(receipt.get(key) != value for key, value in {'batch_id': batch, 'endpoint': endpoint,
                                                          'fingerprint': fingerprint, 'case': case}.items()):
            raise RuntimeError('EXISTING_RECEIPT_SCOPE_OR_INPUT_CHANGED')
        if receipt.get('submission') == 'rejected':
            if case == 'invalid-manifest' and receipt.get('passed'):
                return receipt
            raise RuntimeError('ORIGINAL_ADMISSION_REJECTED_USE_NEW_CORRECTED_CASE')
    else:
        task = 'complex-' + uuid.uuid4().hex
        execution = {'taskId': task, 'language': 'node' if case == 'node' else 'python', 'code': code,
                     'dependencies': [], 'inputs': [], 'scriptPath': None, 'programTools': False,
                     'deadlineAt': (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(),
                     'executionPolicy': {'revision': 1, 'executionTimeoutSeconds': 120,
                         'dockerControlTimeoutSeconds': 10, 'workspaceRetentionHours': 24,
                         'workspaceCleanupIntervalMinutes': 60, 'outputCaptureBytes': 4194304,
                         'maxArtifactFiles': 2048, 'maxConcurrentExecutions': 4, 'memoryLimit': '1g',
                         'cpuLimit': 2, 'pidsLimit': 256}, 'outputFiles': OUTPUTS,
                     'profile': 'STANDARD', 'environmentVersion': None, 'inputJson': None}
        archive_data = io.BytesIO()
        if case == 'invalid-manifest':
            execution['untrustedOwnerInjection'] = 'must-be-rejected-before-submission'
        with zipfile.ZipFile(archive_data, 'w', zipfile.ZIP_STORED) as archive:
            archive.writestr('request.json', json.dumps({'execution': execution, 'artifacts': metadata}))
            for name, content in sorted(inputs.items()):
                archive.writestr(name, content + b'\ncorrupt' if case == 'corrupt-input' and name == 'data/data.json' else content)
        receipt = {'batch_id': batch, 'case': case, 'endpoint': endpoint, 'task_id': task,
                   'fingerprint': fingerprint, 'submission': 'unknown', 'paid_model_calls': 0}
        save(receipt_file, receipt)
        try:
            with request('POST', '/artifact-inputs', archive_data.getvalue()) as response:
                receipt['admission'] = json.load(response)
        except urllib.error.HTTPError as error:
            receipt.update(submission=admission_failure(error), http_status=error.code)
            receipt['passed'] = case == 'invalid-manifest' and receipt['submission'] == 'rejected'
            save(receipt_file, receipt)
            if receipt['passed']:
                return receipt
            raise RuntimeError('SUBMISSION_REJECTED_USE_NEW_CORRECTED_CASE' if receipt['submission'] == 'rejected'
                               else 'SUBMISSION_UNKNOWN_QUERY_ORIGINAL_TASK') from None
        except (OSError, ValueError, RuntimeError):
            save(receipt_file, receipt)
            raise RuntimeError('SUBMISSION_UNKNOWN_QUERY_ORIGINAL_TASK') from None
        receipt['submission'] = 'accepted'
        save(receipt_file, receipt)
        if case == 'invalid-manifest':
            raise RuntimeError('UNKNOWN_MANIFEST_FIELD_WAS_ADMITTED')
    began = time.monotonic()
    while True:
        with request('GET', '/detail?' + urllib.parse.urlencode({'taskId': receipt['task_id']})) as response:
            detail = json.load(response)
        receipt['detail'] = detail
        save(receipt_file, receipt)
        if detail['state'] not in ACTIVE_STATES:
            break
        if time.monotonic() - began >= wait:
            return receipt
        time.sleep(1)
    if detail['state'] == 'unknown':
        raise RuntimeError('RESULT_UNKNOWN_NO_FILES_LOGS_OR_REPLAY')
    if case == 'corrupt-input':
        if (detail['state'] != 'failed' or detail.get('phase') != 'prepare'
                or detail.get('error') != 'WORKSPACE_PREPARATION_FAILED' or detail.get('files')
                or detail.get('exitCode') is not None):
            raise AssertionError('CORRUPT_TRANSFER_MUST_FAIL_PREPARATION_BEFORE_EXECUTION')
        receipt.update(passed=True, corruption_detected_before_execution=True)
        save(receipt_file, receipt)
        return receipt
    if detail['state'] != 'completed' or detail['exitCode'] != 0 or detail.get('deliveryError'):
        for stream in ('stdout', 'stderr'):
            with request('GET', '/logs?' + urllib.parse.urlencode({'taskId': receipt['task_id'], 'stream': stream})) as response:
                save(output / (stream + '.json'), json.load(response))
        raise RuntimeError('COMPLEX_EXECUTION_FAILED_PRIVATE_LOGS_SAVED')
    with request('GET', '/files?' + urllib.parse.urlencode({'taskId': receipt['task_id']})) as response:
        content = response.read(32 * 1024 * 1024 + 1)
    if len(content) > 32 * 1024 * 1024:
        raise RuntimeError('COMPLEX_OUTPUT_TOO_LARGE')
    expected = {item['path']: item for item in detail['files']}
    if set(expected) != set(OUTPUTS):
        raise AssertionError('DECLARED_FILES_DIFFER_FROM_DETAIL')
    downloaded = {}
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        if len(archive.namelist()) != len(OUTPUTS) or set(archive.namelist()) != set(OUTPUTS):
            raise AssertionError('DECLARED_FILES_DIFFER_FROM_ARCHIVE')
        for name in OUTPUTS:
            data = archive.read(name)
            if len(data) != expected[name]['size'] or hashlib.sha256(data).hexdigest() != expected[name]['sha256']:
                raise AssertionError('DOWNLOAD_MANIFEST_SIZE_OR_SHA256_MISMATCH')
            downloaded[name] = data
            destination = output / 'files' / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    result = json.loads(downloaded['reports/totals.json'])
    expectation = {key: oracle[key] for key in ('rows', 'total_cents', 'department_totals_cents',
                                                'pending_rows', 'confirmed_rows', 'null_owners')}
    expectation['input_sha256'] = {item['path']: item['sha256'] for item in oracle['files']}
    if result != expectation:
        raise AssertionError('INDEPENDENT_72_ROW_ORACLE_MISMATCH')
    original, _ = fixture()
    if downloaded['site/index.html'] != original['index.html'] or downloaded['site/data.json'] != original['data.json']:
        raise AssertionError('REFERENCED_SITE_ASSET_BYTES_CHANGED')
    if '72条记录，金额903600分；待审批18条，已确认54条，负责人为空8条。' not in downloaded['reports/summary.md'].decode('utf-8'):
        raise AssertionError('PUBLISHED_SUMMARY_MISMATCH')
    receipt.update(passed=True, files_verified=True, rows=oracle['rows'], total_cents=oracle['total_cents'],
                   unlisted_private_file_excluded=True)
    save(receipt_file, receipt)
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service-config', type=Path, required=True)
    parser.add_argument('--batch-id', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=('python', 'node', 'corrupt-input', 'invalid-manifest'), action='append')
    parser.add_argument('--wait', type=int, default=45)
    args = parser.parse_args()
    if not 0 <= args.wait <= 50:
        parser.error('--wait must be between 0 and 50 seconds')
    results = [execute(args.service_config, args.batch_id, args.evidence / case, case, args.wait)
               for case in (args.case or ('python', 'node', 'corrupt-input', 'invalid-manifest'))]
    print(json.dumps({'paid_model_calls': 0, 'cases': [{'case': value['case'], 'passed': value.get('passed', False),
                      'state': value.get('detail', {}).get('state', value['submission'])} for value in results]}))
