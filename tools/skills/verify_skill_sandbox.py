"""通过现有沙箱的真实安装/执行/文件接口验证指定 Skill；未知请求只查询原任务。"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from package_skills import SKILLS, package_files, ROOT, NAMES
from sandbox_acceptance_support import ACTIVE_STATES, NoRedirect, admission_failure, configuration


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skill', required=True, choices=NAMES)
    parser.add_argument('--example', required=True, help='包内 examples 路径')
    parser.add_argument('--output', type=Path, required=True, help='本用例独立证据目录')
    parser.add_argument('--entry', help='manifest 的 alternate_entries 脚本路径')
    parser.add_argument('--input-name', help='受管 data 输入文件名；新增包必须显式声明')
    parser.add_argument('--input-json', type=Path, help='替换已声明样例的 JSON 内容，用于明确验收输入')
    parser.add_argument('--output-format', help='manifest.outputs 的格式键')
    parser.add_argument('--output-file', action='append', default=[], help='额外登记 manifest 中明确列出的验收文件')
    parser.add_argument('--artifact', action='append', default=[], metavar='RELATIVE_PATH=LOCAL_FILE',
                        help='显式挂载到 artifacts/ 下的验收文件；不从历史工作区猜测输入')
    parser.add_argument('--service-config', type=Path, help='本批私有JSON，只有endpoint/token；不读取根.env')
    parser.add_argument('--batch-id', default=os.environ.get('FULL_TEST_BATCH'), help='显式批次身份，绑定既有回执')
    parser.add_argument('--wait', type=int, default=45, help='本次原任务观察上限0至50秒；重复执行只查询原任务')
    args = parser.parse_args()
    if not 0 <= args.wait <= 50:
        parser.error('--wait must be between 0 and 50 seconds')
    endpoint, token = configuration(args.service_config, args.batch_id)
    files,version = package_files(args.skill)
    manifest = json.loads(files['manifest.json'])
    entry = dict(manifest['entry'], dependencies=manifest.get('dependencies', []))
    if args.entry:
        entry = next(x for x in manifest.get('alternate_entries', []) if x['script_path'] == args.entry)
    if args.example not in manifest['example_inputs']:
        raise ValueError('example is not declared by the package')
    inputs = dict(files)
    filename = args.input_name or manifest.get('input_name') or {'document':'document.json',
                'spreadsheet':'spreadsheet.json','presentation':'presentation.json',
                'web-app':'react-app.json' if args.entry else 'site.json',
                'configuration':'configuration.json','code-delivery':'delivery.json'}.get(args.skill)
    if not filename or PurePosixPath(filename).name != filename or '\\' in filename or ':' in filename:
        raise ValueError('requires one safe explicit input filename')
    inputs['data/' + filename] = inputs[args.example]
    if args.input_json:
        replacement = args.input_json.read_bytes()
        json.loads(replacement)
        inputs['data/' + filename] = replacement
    for declaration in args.artifact:
        relative, local = declaration.split('=', 1)
        path = PurePosixPath(relative)
        if path.is_absolute() or len(path.parts) < 2 or '..' in path.parts or '\\' in relative or ':' in relative:
            raise ValueError('artifact requires safe artifact_id/file relative path')
        target = 'artifacts/' + relative
        if target in inputs:
            raise ValueError('duplicate explicit input')
        inputs[target] = Path(local).read_bytes()
    data = json.loads(inputs['data/' + filename])
    output_format = args.output_format or manifest.get('default_output_format') or (
                     'html' if args.skill=='web-app' else 'pptx' if args.skill=='presentation' else
                     'xlsx' if args.skill=='spreadsheet' else data.get('format', 'json') if args.skill=='configuration' else
                     'docx' if data.get('format') in ('docx','pdf') else 'markdown')
    if output_format not in manifest['outputs']:
        raise ValueError('requires an output format declared by the package')
    outputs = [p for p in manifest['outputs'][output_format] if not p.endswith('/')]
    declared_outputs = {path for paths in manifest['outputs'].values() for path in paths}
    for path in args.output_file:
        relative = PurePosixPath(path)
        if relative.is_absolute() or '..' in relative.parts or '\\' in path or ':' in path or path.endswith('/'):
            raise ValueError('extra output requires a safe file path')
        if path not in declared_outputs and not any(path.startswith(prefix) for prefix in declared_outputs if prefix.endswith('/')):
            raise ValueError('extra output must be declared by the package')
    outputs = list(dict.fromkeys([*outputs, *args.output_file]))
    if data.get('bundle_zip'):
        outputs.extend(manifest['outputs']['zip'])
    expects_typed_results = manifest.get('expects_typed_results', False)
    if type(expects_typed_results) is not bool:
        raise ValueError('expects_typed_results must be a boolean expectation')
    if data.get('operation', data.get('action')) == 'inspect':
        outputs, expects_typed_results = [], True
    if args.skill == 'research' and data.get('mode') == 'report':
        expects_typed_results = False
    metadata = [{'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()} for path,data in sorted(inputs.items())]
    fingerprint = hashlib.sha256(json.dumps({'inputs':metadata,'entry':entry,'outputs':outputs,
                                            'result_protocol':'fixed-typed-result-v1',
                                            'expects_typed_results':expects_typed_results},sort_keys=True).encode()).hexdigest()
    args.output.mkdir(parents=True,exist_ok=True)
    receipt_file = args.output / 'execution.json'
    base = endpoint + '/internal/executions'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(method,path,body=None,content_type='application/json'):
        req = urllib.request.Request(base+path, data=body, method=method,
            headers={'Authorization':'Bearer '+token,'Content-Type':content_type})
        try:
            return opener.open(req,timeout=90)
        except urllib.error.HTTPError:
            raise

    def save(receipt):
        receipt_file.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

    if receipt_file.exists():
        receipt = json.loads(receipt_file.read_text(encoding='utf-8'))
        if receipt['fingerprint'] != fingerprint or receipt.get('batch_id') != args.batch_id or receipt.get('endpoint') != endpoint:
            raise ValueError('evidence input changed; use a new independent case directory')
        if receipt.get('submission') == 'rejected':
            raise RuntimeError('ORIGINAL_ADMISSION_REJECTED_USE_NEW_CORRECTED_CASE')
    else:
        task_id = 'skill-adoption-' + uuid.uuid4().hex
        control = {'taskId':task_id,'language':entry['language'],'code':'','dependencies':entry.get('dependencies',[]),
            'inputs':metadata,'scriptPath':entry['script_path'],'programTools':False,
            'deadlineAt':(datetime.now(timezone.utc)+timedelta(minutes=20)).isoformat(),
            'executionPolicy':{'revision':1,'executionTimeoutSeconds':900,'dockerControlTimeoutSeconds':10,
                'workspaceRetentionHours':24,'workspaceCleanupIntervalMinutes':60,'outputCaptureBytes':4194304,
                'maxArtifactFiles':2048,'maxConcurrentExecutions':4,'memoryLimit':'1g','cpuLimit':2,'pidsLimit':256},
            'outputFiles':outputs,'profile':'STANDARD','environmentVersion':None,'inputJson':None}
        archive_data = io.BytesIO()
        with zipfile.ZipFile(archive_data,'w',zipfile.ZIP_STORED) as archive:
            archive.writestr('request.json',json.dumps({'execution':control,'artifacts':metadata}))
            for path,data in sorted(inputs.items()):
                archive.writestr(path,data)
        receipt = {'batch_id':args.batch_id,'endpoint':endpoint,'fingerprint':fingerprint,'task_id':task_id,'skill':args.skill,'version':version,'entry':entry,'submission':'unknown','paid_model_calls':0}
        save(receipt)
        try:
            with request('POST','/artifact-inputs',archive_data.getvalue(),'application/zip') as response:
                receipt['admission'] = json.load(response)
        except urllib.error.HTTPError as error:
            receipt['submission'] = admission_failure(error)
            receipt['http_status'] = error.code
            save(receipt)
            raise RuntimeError('SANDBOX_SUBMISSION_' + receipt['submission'].upper()) from None
        except (OSError, ValueError, RuntimeError):
            save(receipt)
            raise RuntimeError('SANDBOX_SUBMISSION_UNKNOWN_QUERY_ORIGINAL_RECEIPT') from None
        receipt['submission']='accepted'
        save(receipt)
    started = time.monotonic()
    while True:
        with request('GET','/detail?taskId='+receipt['task_id']) as response:
            detail = json.load(response)
        receipt['detail']=detail
        save(receipt)
        if detail['state'] not in ACTIVE_STATES:
            break
        if time.monotonic()-started > args.wait:
            print(json.dumps({'skill':args.skill,'state':detail['state'],'task_id':receipt['task_id']}))
            return
        time.sleep(1)
    if detail['state'] == 'unknown':
        raise RuntimeError('SANDBOX_RESULT_UNKNOWN_NO_FILES_OR_REPLAY')
    if detail['state']!='completed' or detail['exitCode']!=0 or detail.get('deliveryError'):
        for stream in ('stdout','stderr'):
            with request('GET',f'/logs?taskId={receipt["task_id"]}&stream={stream}') as response:
                log=json.load(response)
            (args.output/(stream+'.json')).write_text(json.dumps(log,ensure_ascii=False),encoding='utf-8')
        raise RuntimeError('skill execution failed; see private evidence logs')
    if expects_typed_results:
        public = detail.get('publicResults')
        if not isinstance(public, dict) or not isinstance(public.get('results'), list) or not public['results']:
            raise ValueError('fixed result.json did not return expected typed results')
        receipt['typed_results_verified'] = True
    with request('GET','/files?taskId='+receipt['task_id']) as response:
        content=response.read(32*1024*1024+1)
    if len(content)>32*1024*1024:
        raise ValueError('synthetic case output exceeds 32 MiB')
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        expected={x['path']:x for x in detail['files']}
        if len(archive.namelist()) != len(expected) or set(archive.namelist())!=set(expected):
            raise ValueError('download manifest differs')
        for path in archive.namelist():
            data=archive.read(path)
            if len(data)!=expected[path]['size'] or hashlib.sha256(data).hexdigest()!=expected[path]['sha256']:
                raise ValueError('download digest differs')
            target=args.output/'files'/path
            if not target.resolve().is_relative_to((args.output/'files').resolve()):
                raise ValueError('unsafe output path')
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(data)
    receipt['files_verified']=True
    save(receipt)
    print(json.dumps({'skill':args.skill,'state':detail['state'],'task_id':receipt['task_id'],'files':list(expected)}))


if __name__=='__main__':
    main()
