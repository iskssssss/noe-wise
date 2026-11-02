"""开发库真实附件上传与 document-read 模型验收；未知写入只查询原回执。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import time
from urllib.parse import urlencode, urlparse
import uuid

import requests
import verify_live_skills as live

EVIDENCE = None
AGENT_KEY = None
PACKAGES_REPORT = None
FORMATS = {'pdf': 'application/pdf', 'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
           'pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
           'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
           'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg'}
TERMINAL = {'completed', 'failed', 'cancelled', 'outcome_unknown', 'waiting_hitl'}


def write_once(client, route, *, body=None, fields=None, files=None):
    """写入不自动重试，包含 HTTP 429 与重定向。"""
    origin = client.origins['institution']
    parsed = urlparse(origin)
    if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1'):
        raise RuntimeError('LOCAL_DEVELOPMENT_ORIGIN_REQUIRED')
    client.ensure_budget(query_only=route == '/chat/runs/cancel')
    if route == '/chat':
        client.before_chat(body)
    try:
        response = requests.post(origin + '/api/v1' + route, headers=client.headers['institution'],
                                 json=body, data=fields, files=files, timeout=45, allow_redirects=False)
    except requests.RequestException:
        raise RuntimeError('WRITE_OUTCOME_UNKNOWN_QUERY_ORIGINAL_RECEIPT') from None
    try:
        envelope = response.json()
    except ValueError:
        raise RuntimeError('WRITE_OUTCOME_UNKNOWN_INVALID_RESPONSE') from None
    if response.status_code >= 500:
        raise RuntimeError('WRITE_OUTCOME_UNKNOWN_HTTP_' + str(response.status_code))
    if not response.ok or envelope.get('code') != 'OK':
        # 不回显供应商正文、访问令牌或上传文档内容。
        raise RuntimeError('WRITE_NOT_ACCEPTED_HTTP_' + str(response.status_code))
    result = envelope.get('data')
    if route == '/chat':
        client.after_chat(body, result)
    return result


def source_fact(path):
    path = path.resolve(strict=True)
    extension = path.suffix.lower().lstrip('.')
    if not path.is_file() or extension not in FORMATS:
        raise ValueError('SUPPORTED_DOCUMENT_FILE_REQUIRED')
    if len(path.name) > 128 or any(ord(c) < 32 or ord(c) == 127 for c in path.name):
        raise ValueError('SAFE_ATTACHMENT_NAME_REQUIRED')
    size = path.stat().st_size
    if not 0 < size <= 50 * 1024 * 1024:
        raise ValueError('ATTACHMENT_MUST_BE_BETWEEN_1_BYTE_AND_50_MIB')
    content = path.read_bytes()
    if len(content) != size:
        raise ValueError('SOURCE_CHANGED_DURING_READ')
    return {'path': str(path), 'name': path.name, 'format': extension, 'size': size,
            'sha256': hashlib.sha256(content).hexdigest()}, content


def prompt_for(fact):
    scope = ('使用 structured PDF pipeline，读取第1至2页（文件不足2页时只读实际页数），报告真实总页数、'
             '选定范围、表格和关键原文。' if fact['format'] == 'pdf' else
             '读取原生文字与表格；图片用本地OCR，说明识别局限。Office不虚构打印页数或重算公式。')
    return ('请实际使用 document-read Skill 读取本条消息上传的附件“' + fact['name'] + '”。'
            '先加载Skill并读取manifest与输入契约，使用附件服务器清单的真实id、input_path与摘要，'
            '在code_execute的attachment_ids中显式选择该附件；source_path使用/input/data/下的真实附件路径。'
            '只执行scripts/read_document.py，dependencies为[]，action=read，类型化结果写固定/workspace/output/result.json，'
            'output_files=[]，不要创建或正式交付文件。' + scope +
            '依据实际类型化读取结果给出摘要；解析缺失、失败或部分范围时如实说明，不能根据文件名猜测内容。')


def validate_metadata(metadata, fact, upload_id):
    expected = {'id': upload_id, 'name': fact['name'], 'size': fact['size'], 'sha256': fact['sha256'],
                'input_path': 'attachments/' + upload_id + '/input.' + fact['format']}
    if not isinstance(metadata, dict) or any(metadata.get(k) != v for k, v in expected.items()):
        raise RuntimeError('ATTACHMENT_READBACK_DIFFERS')
    if not metadata.get('expires_at'):
        raise RuntimeError('ATTACHMENT_EXPIRY_REQUIRED')


def owned_agent(client, frozen=None):
    receipt = live.support.load(EVIDENCE / 'agent.json')
    if not receipt or receipt.get('state') != 'published' or not receipt.get('agent_id'):
        raise RuntimeError('STRENGTHENING_PUBLISHED_AGENT_RECEIPT_REQUIRED')
    aid = str(frozen['agent_id'] if frozen else receipt['agent_id'])
    owned = {str(receipt['agent_id']), *(str(value) for value in receipt.get('owned_agent_ids', []))}
    if aid not in owned:
        raise RuntimeError('FROZEN_AGENT_NOT_IN_TRUSTED_OWNERSHIP_RECEIPT')
    agent = client.api('institution', '/agents/detail?' + urlencode({'agentId': aid}))
    if agent.get('agent_key') != AGENT_KEY or agent.get('status') != 'published':
        raise RuntimeError('OWNED_PUBLISHED_STRENGTHENING_AGENT_REQUIRED')
    report = live.support.load(PACKAGES_REPORT)
    package = next((p for p in (report or {}).get('packages', []) if p['name'] == 'document-read'), None)
    if not package or package.get('version') != '1.0.1' or not package.get('publication'):
        raise RuntimeError('PUBLISHED_DOCUMENT_READ_PACKAGE_REQUIRED')
    mounts = client.api('institution', '/agents/capability-mounts?' + urlencode({'agentId': aid}))['mounts']
    target = frozen['package'] if frozen else package['publication']
    if any(target.get(key) != package['publication'].get(key)
           for key in ('definition_id', 'version_id', 'frozen_file_sha256')):
        raise RuntimeError('FROZEN_DOCUMENT_READ_VERSION_OR_FILES_CHANGED')
    if not any(str(m['capability_definition_id']) == str(target['definition_id'])
               and str(m['capability_version_id']) == str(target['version_id']) and m['status'] == 'ACTIVE' for m in mounts):
        raise RuntimeError('EXACT_DOCUMENT_READ_MOUNT_REQUIRED')
    return aid, target


def prepare(client, args, directory):
    fact, content = source_fact(args.file)
    path = directory / 'receipt.json'
    receipt = live.support.load(path)
    aid, target = owned_agent(client, receipt['input'] if receipt else None)
    expected = {'source': fact, 'prompt': prompt_for(fact), 'agent_id': aid,
                'package': target, 'expected_text': args.expect_text}
    if receipt and receipt['input'] != expected:
        raise RuntimeError('LIVE_INPUT_CHANGED_USE_AN_INDEPENDENT_CASE')
    if not receipt:
        uid = str(client.access['profile:0']['end_user_id'])
        receipt = {'state': 'creating_session', 'input': expected,
                   'session_request': {'agent_id': aid, 'end_user_id': uid},
                   'upload_id': str(uuid.uuid4()), 'client_message_id': str(uuid.uuid4())}
        live.support.save(path, receipt)
        session = write_once(client, '/chat/sessions', body=receipt['session_request'])
        receipt.update(state='session_created', session_id=str(session['session_id']))
        live.support.save(path, receipt)
    elif receipt['state'] == 'creating_session':
        raise RuntimeError('UNKNOWN_SESSION_CREATION_REQUIRES_RECONCILIATION')
    sid, upload_id = receipt['session_id'], receipt['upload_id']
    attachments = client.api('institution', '/chat/sessions/attachments?' + urlencode({'session_id': sid}))
    matches = [m for m in attachments if m['id'] == upload_id]
    if matches:
        if len(matches) != 1:
            raise RuntimeError('DUPLICATE_ATTACHMENT_METADATA')
        validate_metadata(matches[0], fact, upload_id)
        receipt['attachment'] = matches[0]
        if receipt['state'] in ('uploading', 'session_created'):
            receipt['state'] = 'uploaded'
        live.support.save(path, receipt)
    elif receipt['state'] in ('session_created', 'upload_rejected'):
        if receipt['state'] == 'upload_rejected':
            rejection = receipt.get('rejection', {})
            if not args.retry_rejected_upload or rejection.get('reason') != 'database_constraint_rejected' or not rejection.get('original_uuid_absent'):
                raise RuntimeError('EXPLICIT_CONFIRMED_REJECTION_RETRY_REQUIRED')
            receipt.setdefault('previous_rejections', []).append(rejection)
        receipt['state'] = 'uploading'
        live.support.save(path, receipt)
        metadata = write_once(client, '/chat/sessions/attachments/upload',
                              fields={'session_id': sid, 'upload_id': upload_id},
                              files={'file': (fact['name'], content, FORMATS[fact['format']])})
        validate_metadata(metadata, fact, upload_id)
        receipt.update(state='uploaded', attachment=metadata)
        live.support.save(path, receipt)
    elif not receipt.get('response'):
        raise RuntimeError('UNKNOWN_UPLOAD_QUERY_FOUND_NO_ORIGINAL_UUID')
    if receipt['state'] == 'uploaded':
        body = {'session_id': sid, 'agent_id': aid, 'client_message_id': receipt['client_message_id'],
                'content': [{'type': 'text', 'text': expected['prompt']}],
                'context': {'end_user_id': receipt['session_request']['end_user_id'], 'attachment_ids': [upload_id]}}
        receipt.update(state='submitting', request=body)
        live.support.save(path, receipt)
        receipt.update(state='submitted', response=write_once(client, '/chat', body=body))
        live.support.save(path, receipt)
    if not receipt.get('response', {}).get('execution_id'):
        raise RuntimeError('UNKNOWN_CHAT_ACCEPTANCE_REQUIRES_RECONCILIATION')
    return receipt


def known_status(client, receipt, directory):
    """同一 Run 每 60 秒至多查询一次；终态沿用已保存事实。"""
    saved = live.support.load(directory / 'status.json')
    poll = live.support.load(directory / 'status-poll.json') or {}
    if saved and (saved.get('status') in TERMINAL or time.time() - poll.get('queried_at', 0) < 60):
        return saved
    live.support.save(directory / 'status-poll.json', {'queried_at': time.time()})
    status = client.api('institution', '/chat/runs/status?' + urlencode(
        {'session_id': receipt['session_id'], 'run_id': receipt['response']['execution_id']}))
    live.support.save(directory / 'status.json', status)
    return status


def cancel_original(client, directory):
    receipt = live.support.load(directory / 'receipt.json')
    if not receipt or not receipt.get('response', {}).get('execution_id'):
        raise RuntimeError('DEFINITE_ORIGINAL_RUN_RECEIPT_REQUIRED')
    status = known_status(client, receipt, directory)
    if status['status'] in TERMINAL:
        print(json.dumps({'case': directory.name, 'run_id': receipt['response']['execution_id'],
                          'status': status['status'], 'cancellation': 'already_terminal'}))
        return
    path = directory / 'cancel.json'
    cancellation = live.support.load(path)
    if not cancellation:
        body = {'session_id': receipt['session_id'], 'run_id': receipt['response']['execution_id']}
        cancellation = {'state': 'unknown', 'request': body, 'requested_at': time.time()}
        live.support.save(path, cancellation)
        cancellation.update(state='accepted', response=write_once(client, '/chat/runs/cancel', body=body))
        live.support.save(path, cancellation)
    print(json.dumps({'case': directory.name, 'run_id': receipt['response']['execution_id'],
                      'cancellation': cancellation['state']}))


def recover(client, original_directory, directory):
    """明确原 Run 已失败/取消后，在原会话和附件上提交新输入，保留原失败回执。"""
    original = live.support.load(original_directory / 'receipt.json')
    if not original or not original.get('response', {}).get('execution_id'):
        raise RuntimeError('DEFINITE_ORIGINAL_RUN_RECEIPT_REQUIRED')
    status = known_status(client, original, original_directory)
    if status['status'] not in {'failed', 'cancelled', 'completed'}:
        raise RuntimeError('KNOWN_ORIGINAL_TERMINAL_REQUIRED_BEFORE_RECOVERY')
    if status['status'] == 'completed':
        assessment = live.support.load(original_directory / 'assessment.json')
        if not assessment or assessment.get('passed'):
            raise RuntimeError('CONFIRMED_FAILED_ORIGINAL_ACCEPTANCE_REQUIRED')
    aid, _ = owned_agent(client, original['input'])
    fact, _ = source_fact(Path(original['input']['source']['path']))
    if fact != original['input']['source']:
        raise RuntimeError('ORIGINAL_SOURCE_CHANGED')
    attachments = client.api('institution', '/chat/sessions/attachments?' + urlencode(
        {'session_id': original['session_id']}))
    matches = [m for m in attachments if m['id'] == original['upload_id']]
    if len(matches) != 1:
        raise RuntimeError('ORIGINAL_ATTACHMENT_REQUIRED')
    validate_metadata(matches[0], fact, original['upload_id'])
    path = directory / 'receipt.json'
    receipt = live.support.load(path)
    if receipt:
        if (receipt.get('recovery_of') != original['response']['execution_id']
                or receipt.get('input') != original['input']):
            raise RuntimeError('RECOVERY_RECEIPT_DOES_NOT_MATCH_ORIGINAL')
        if not receipt.get('response', {}).get('execution_id'):
            raise RuntimeError('UNKNOWN_RECOVERY_ACCEPTANCE_QUERY_ORIGINAL_CLIENT_MESSAGE_ID')
        return receipt
    client_id = str(uuid.uuid4())
    body = {'session_id': original['session_id'], 'agent_id': aid, 'client_message_id': client_id,
            'content': [{'type': 'text', 'text': original['input']['prompt']}],
            'context': {'end_user_id': original['session_request']['end_user_id'],
                        'attachment_ids': [original['upload_id']]}}
    receipt = {'state': 'submitting', 'input': original['input'], 'session_request': original['session_request'],
               'session_id': original['session_id'], 'upload_id': original['upload_id'],
               'attachment': matches[0], 'client_message_id': client_id, 'request': body,
               'recovery_of': original['response']['execution_id']}
    live.support.save(path, receipt)
    receipt.update(state='submitted', response=write_once(client, '/chat', body=body))
    live.support.save(path, receipt)
    if not receipt.get('response', {}).get('execution_id'):
        raise RuntimeError('UNKNOWN_RECOVERY_ACCEPTANCE_QUERY_ORIGINAL_CLIENT_MESSAGE_ID')
    return receipt


def documents(value):
    """读取结构化结果 JSON，包含 Runtime 的 canonicalJson 包装与正文内摘要。"""
    if isinstance(value, dict):
        yield value
        for key, child in value.items():
            if key in ('canonicalJson', 'text') and isinstance(child, str):
                try:
                    yield from documents(json.loads(child))
                except ValueError:
                    pass
            else:
                yield from documents(child)
    elif isinstance(value, list):
        for child in value:
            yield from documents(child)


def collect_trace(client, receipt, directory):
    params = {'tenant_id': str(client.state['resources']['tenant']['id']),
              'session_id': receipt['session_id'], 'root_input_id': receipt['response']['input_id'], 'page_size': 100}
    pages, values, cursors = [], [], set()
    for _ in range(20):
        page = client.api('platform', '/platform/sessions/trace?' + urlencode(params))
        pages.append(page)
        for event in page.get('items', []):
            if event.get('run_id') != receipt['response']['execution_id'] or not event.get('content_ref'):
                continue
            if event.get('event_type') not in ('agent.tool-result', 'agent.model-result') and not str(event.get('event_id', '')).startswith(('skill-activation:', 'skill-resource:')):
                continue
            value = client.api('platform', '/platform/sessions/trace/content?' + urlencode(
                {k: v for k, v in params.items() if k not in ('page_size', 'cursor')} | {'content_ref': event['content_ref']}))
            values.append({'event': event, 'content': value})
        cursor = page.get('next_cursor')
        if not cursor:
            live.support.save(directory / 'trace.json', pages)
            live.support.save(directory / 'trace-content.json', values)
            return values, True
        if cursor in cursors:
            raise RuntimeError('TRACE_CURSOR_DID_NOT_ADVANCE')
        cursors.add(cursor)
        params['cursor'] = cursor
    live.support.save(directory / 'trace.json', pages)
    live.support.save(directory / 'trace-content.json', values)
    return values, False


def skill_facts(values, target):
    handles, activated = set(), set()
    for entry in values:
        payload = entry['content']
        if payload.get('availability') != 'FULL':
            continue
        value = payload.get('value', {})
        schema = payload.get('schema_id')
        if schema == 'skillforge.runtime.session.skill-activation.v1':
            activated.update(value.get('afterHandles', []))
        if schema not in {'skillforge.runtime.session.skill-activation.v1',
                          'skillforge.runtime.session.skill-resource.v1'}:
            continue
        for resource in value.get('resources', []):
            if (str(resource.get('capabilityId')) == str(target['definition_id'])
                    and str(resource.get('versionId')) == str(target['version_id'])
                    and resource.get('status') == 'LOADED'):
                handles.add(resource['handle'])
    return handles, activated


def collect(client, directory, wait):
    receipt = live.support.load(directory / 'receipt.json')
    if not receipt or not receipt.get('response', {}).get('execution_id'):
        raise RuntimeError('DEFINITE_CHAT_ACCEPTANCE_REQUIRED_NO_WRITE_REPLAY')
    sid, run_id = receipt['session_id'], receipt['response']['execution_id']
    if wait:
        time.sleep(wait)
    status = known_status(client, receipt, directory)
    if status['status'] not in TERMINAL:
        print(json.dumps({'case': directory.name, 'run_id': run_id, 'status': status['status'], 'acceptance': 'pending'}))
        return
    client.settle_budget()
    terminal_evidence = live.support.load(directory / 'terminal-evidence.json')
    if not terminal_evidence or terminal_evidence.get('run_id') != run_id:
        history = client.api('institution', '/chat/history?' + urlencode({'session_id': sid}))
        live.support.save(directory / 'history.json', history)
        values, trace_complete = collect_trace(client, receipt, directory)
        if trace_complete:
            live.support.save(directory / 'terminal-evidence.json', {'run_id': run_id, 'collected_at': time.time()})
    else:
        history = live.support.load(directory / 'history.json')
        values = live.support.load(directory / 'trace-content.json')
        trace_complete = True
    target = receipt['input']['package']
    handles, activated = skill_facts(values, target)
    reused_activation = None
    if not handles & activated and receipt.get('recovery_of'):
        original_directory = directory.parents[1]
        original = live.support.load(original_directory / 'receipt.json')
        if (not original or original.get('session_id') != sid or original.get('upload_id') != receipt['upload_id']
                or original.get('input') != receipt['input']
                or original.get('response', {}).get('execution_id') != receipt['recovery_of']):
            raise RuntimeError('HISTORICAL_SKILL_FACTS_REQUIRE_EXACT_ORIGINAL_RECEIPT')
        # 仅复用同会话已证实的精确激活事实，绝不合并原 Run 调用或结果。
        old_handles, old_activated = skill_facts(
            live.support.load(original_directory / 'trace-content.json') or [], target)
        if old_handles & old_activated:
            handles |= old_handles
            activated |= old_activated
            reused_activation = receipt['recovery_of']
    results = []
    calls = []
    for entry in values:
        payload = entry['content']
        value = payload.get('value', {})
        if payload.get('schema_id') == 'skillforge.runtime.agent.model-result.v3':
            # REDACTED 结果只读取 API 实际保留的动作参数，不推断隐藏的思考或缺失参数。
            for decision in documents(value.get('output')):
                if decision.get('type') == 'tool_call' and isinstance(decision.get('arguments'), dict):
                    calls.append({'operation': value.get('operation'), 'decision': decision})
            continue
        if payload.get('availability') != 'FULL':
            continue
        if payload.get('schema_id') == 'skillforge.runtime.agent.tool-result.v1' and value.get('status') == 'SUCCEEDED':
            results.append(value)
    fact = receipt['input']['source']
    read_outputs = []
    for result in results:
        parsed = list(documents(result.get('output')))
        injected = any(any(isinstance(m, dict) and m.get('id') == receipt['upload_id']
                           and m.get('sha256') == fact['sha256'] and m.get('size') == fact['size']
                           for m in d.get('attachment_inputs', [])) for d in parsed)
        summaries = [d for d in parsed if d.get('conversion_status') == 'success' and d.get('source_format') == fact['format']]
        packaged = False
        for call in calls:
            if call['operation'] != result.get('operation') or call['decision'].get('tool_handle') != result.get('toolHandle'):
                continue
            args = call['decision']['arguments']
            if not isinstance(args.get('input_files'), dict):
                continue
            try:
                config = json.loads(args.get('input_files', {}).get('document-read.json', ''))
            except (TypeError, ValueError):
                continue
            if not isinstance(config, dict):
                continue
            if (args.get('mode') == 'skill' and args.get('language') == 'python'
                    and args.get('script_path') == 'scripts/read_document.py'
                    and args.get('skill_handle') in handles & activated
                    and args.get('attachment_ids') == [receipt['upload_id']]
                    and args.get('dependencies', []) == [] and args.get('output_files', []) == []
                    and 'public_result_file' not in args
                    and config.get('source_path') == '/input/data/' + receipt['attachment']['input_path']
                    and config.get('action', 'read') == 'read'):
                packaged = True
        if injected and summaries:
            text = '\n'.join(d['text'] for d in parsed if isinstance(d.get('text'), str) and d.get('format') == 'markdown')
            read_outputs.append({'summary': summaries[0], 'text': text, 'result_ref': result.get('resultRef'),
                                 'packaged_script_call': packaged})
    deliverables = client.api('institution', '/chat/sessions/deliverables?' + urlencode({'sessionId': sid, 'includeHistory': 'true'}))
    live.support.save(directory / 'deliverables.json', deliverables)
    checks = {'run_completed': status['status'] == 'completed', 'complete_trace': trace_complete,
              'document_read_resource_and_activation': bool(handles & activated),
              'actual_attachment_read_result': bool(read_outputs),
              'actual_packaged_script_call': any(r['packaged_script_call'] for r in read_outputs),
              'expected_text_in_reader_result': bool(read_outputs) and all(any(text in r['text'] for r in read_outputs) for text in receipt['input']['expected_text']),
              'no_unrequested_formal_deliverable': not any(run_id in d.get('result_run_ids', []) for d in deliverables)}
    if fact['format'] == 'pdf':
        checks['structured_pdf_pipeline'] = any(r['summary'].get('pipeline') == 'structured' for r in read_outputs)
    assessment = {'case': directory.name, 'session_id': sid, 'run_id': run_id, 'attachment_id': receipt['upload_id'],
                  'checks': checks, 'reader_results': read_outputs, 'passed': all(checks.values()),
                  'skill_activation_reused_from_run': reused_activation}
    live.support.save(directory / 'assessment.json', assessment)
    print(json.dumps({k: v for k, v in assessment.items() if k != 'reader_results'}, ensure_ascii=False))
    if not assessment['passed']:
        raise RuntimeError('LIVE_READ_ACCEPTANCE_FAILED_SEE_PRIVATE_ASSESSMENT')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('run', 'status', 'cancel', 'recover'))
    parser.add_argument('--case', choices=('pdf', 'docx', 'pptx', 'xlsx', 'png', 'jpg', 'jpeg'), required=True)
    parser.add_argument('--file', type=Path)
    parser.add_argument('--expect-text', action='append', default=[], help='核对读取器实际输出，不加入模型提示词')
    parser.add_argument('--retry-rejected-upload', action='store_true',
                        help='明确数据库拒绝且新迁移生效后，沿用原上传 UUID 重试')
    parser.add_argument('--recovery-id', help='原会话恢复输入的稳定回执名；status 可选择同一恢复 Run')
    parser.add_argument('--wait', type=int, default=45)
    live.live_batch.add_arguments(parser)
    parser.add_argument('--profile', choices=('original','candidate'), default='original')
    args = parser.parse_args()
    if not 0 <= args.wait <= 50:
        parser.error('--wait must be between 0 and 50 seconds')
    if args.action == 'run' and (not args.file or args.file.suffix.lower() != '.' + args.case):
        parser.error('run requires --file with the selected case extension')
    if args.recovery_id and (not args.recovery_id.isascii() or not args.recovery_id.replace('-', '').replace('_', '').isalnum()
                             or len(args.recovery_id) > 64):
        parser.error('--recovery-id must be a safe ASCII receipt name')
    if args.action == 'recover' and not args.recovery_id:
        parser.error('recover requires --recovery-id')
    if args.action in ('run', 'cancel') and args.recovery_id:
        parser.error('--recovery-id is only valid for recover or status')
    global EVIDENCE, AGENT_KEY, PACKAGES_REPORT
    scope=live.live_batch.configure(args,'strengthened-'+args.profile)
    EVIDENCE=Path(scope['evidence_root']); AGENT_KEY=scope['agent_key']; PACKAGES_REPORT=Path(scope['packages_report'])
    directory = EVIDENCE / 'document-read' / args.case
    client = live.client(); client.live_scope=scope
    if args.action == 'cancel':
        cancel_original(client, directory)
        return
    original_directory = directory
    if args.recovery_id:
        directory = directory / 'recoveries' / args.recovery_id
    if args.action == 'run':
        prepare(client, args, directory)
    elif args.action == 'recover':
        recover(client, original_directory, directory)
    collect(client, directory, args.wait)


if __name__ == '__main__':
    main()
