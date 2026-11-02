"""W30 正式 HTTP 验收准备：复用独占环境及原回执，禁止写入结果未知后的重建或重发。"""
from __future__ import annotations

import argparse
import base64
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import time
import urllib.parse

from workflow_live_environment import save
from workflow_live_acceptance import Acceptance, SafeFailure, objects, redact, require, typed_exports_present


BASE = '/builder/workflows/test-sessions'
PAYLOAD = {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}
EMPTY_BINDINGS = {'capabilities': [], 'workflows': [], 'agents': []}
BUDGET_FIELDS = ('max_business_model_calls', 'max_tool_calls', 'max_knowledge_calls',
                 'max_total_tokens', 'max_wall_time_seconds')


def query(path, **values):
    return path + '?' + urllib.parse.urlencode(values)


def forbidden_fixture_identity(batch, node_type):
    slug = node_type.replace('_', '-')
    return batch + '-w30-forbidden-' + slug, 'w30-forbidden-source-' + slug


def source_definition(workflow_id):
    """参数按现有 parameterState 进入 ctx_*；不使用 script、工具、异步或模型节点。"""
    return {'version': '1.0', 'entry': 'start', 'meta': {
        'name': workflow_id, 'display_name': '隔离测试会话类型验收', 'version': '1.0.0',
        'description': '纯赋值和输出；不需要模型决定工作流动作',
        'capability': {'model_invocable': False, 'user_invocable': True,
            'input_schema': {'type': 'object', 'properties': {'payload': {'type': 'object'}, 'user_input': {'type': 'string'}},
                             'required': ['payload'], 'additionalProperties': False},
            'output_schema': {'type': 'object', 'properties': {
                'produced': {'type': 'object'}, 'consumed': {'type': 'object'}, 'messages': {'type': 'array', 'items': {'type': 'object'}}},
                'required': ['produced', 'consumed', 'messages'], 'additionalProperties': False},
            'supports_hitl': False, 'export_fields': ['messages', 'produced', 'consumed'],
            'timeout_ms': 30000, 'concurrency_safe': True}, 'config_schema': []},
        'nodes': [{'id': 'start', 'type': 'start'},
                  {'id': 'produce', 'type': 'assign', 'args': {'produced': 'state.ctx_payload'}},
                  {'id': 'consume', 'type': 'assign', 'args': {'consumed': 'state.produced'}},
                  {'id': 'output', 'type': 'output',
                   'message': '${state.consumed.count}|${state.consumed.enabled}|${state.consumed.nested.label}'},
                  {'id': 'end', 'type': 'end'}],
        'edges': [{'from': 'start', 'to': 'produce'}, {'from': 'produce', 'to': 'consume'},
                  {'from': 'consume', 'to': 'output'}, {'from': 'output', 'to': 'end'}]}


def initial_submission(receipt):
    for field in ('initial_input_id', 'initial_message', 'session_id', 'agent_id', 'end_user_id'):
        require(isinstance(receipt.get(field), str) and receipt[field], 'receipt_identity_missing:' + field)
    # 正式 ChatRequest 使用 content；绝不再通过 context.input_id 创建第二个输入身份。
    return {'client_message_id': receipt['initial_input_id'],
            'content': [{'type': 'text', 'text': receipt['initial_message']}],
            'session_id': receipt['session_id'], 'agent_id': receipt['agent_id'],
            'context': {'end_user_id': receipt['end_user_id']}, 'references': []}


def same_identity(first, second):
    fields = ('test_session_id', 'request_id', 'session_id', 'agent_id', 'end_user_id',
              'initial_input_id', 'initial_message', 'source')
    return all(first.get(field) == second.get(field) for field in fields)


def ordinary_session_probe_body(receipt, formal_end_user_id):
    """沿普通 CreateSessionRequest，使用正式用户以免先被私有用户门禁掩盖 Agent 隔离。"""
    require(isinstance(receipt.get('agent_id'), str) and receipt['agent_id'], 'original_private_agent_identity_required')
    require(isinstance(formal_end_user_id, str) and formal_end_user_id, 'formal_baseline_end_user_required')
    return {'agent_id': receipt['agent_id'], 'end_user_id': formal_end_user_id}


def runtime_checks(trace, history, run_status, public_results, initial_input_id):
    """使用当前执行树快照，只依据可见节点与完整结果判断。"""
    nodes = trace.get('nodes', {}).get('items', [])
    workflow_nodes = [node for node in nodes if node.get('kind') == 'WORKFLOW']
    workflow_actions = {node['action_id'] for node in workflow_nodes if node.get('action_id')}
    model_actions = [node for node in nodes if node.get('kind') == 'LLM']
    exports = [value for value in objects(public_results)
               if set(value) == {'messages', 'produced', 'consumed'} and typed_exports_present(value)]
    final_documents = []
    for message in history.get('messages', []):
        if message.get('role') != 'assistant' or not isinstance(message.get('content'), str):
            continue
        try:
            document = json.loads(message['content'])
        except ValueError:
            continue
        if isinstance(document, dict) and typed_exports_present(document) and document in exports:
            final_documents.append(document)
    return {'run_completed': run_status.get('status') == 'completed',
        'one_target_workflow_action': len(workflow_actions) == 1,
        'no_model_decision_action': not model_actions,
        'one_initial_input': trace.get('detail', {}).get('summary', {}).get('root_input_id') == initial_input_id
            and all(node.get('root_input_id') == initial_input_id for node in nodes),
        'complete_node_snapshot': trace.get('nodes', {}).get('coverage') == 'COMPLETE'
            and not trace.get('nodes', {}).get('next_cursor'),
        'no_model_usage': trace.get('detail', {}).get('usage_summary', {}).get('calls') == 0,
        'declared_typed_exports': typed_exports_present(public_results),
        'declared_export_keys_only': bool(exports),
        'formal_workflow_result': any(node.get('status') == 'SUCCEEDED' for node in workflow_nodes)
            and any(result.get('availability') == 'FULL'
                and result.get('schema_id') == 'skillforge.runtime.workflow.engine-result.v1'
                for result in public_results),
        'history_matches_declared_output': bool(final_documents),
        'output_node_message_exact': any(item.get('role') == 'assistant'
            and item.get('content') == '0|false|typed-nested-proof'
            for document in final_documents for item in document.get('messages', []) if isinstance(item, dict))}


class TestSessionAcceptance(Acceptance):
    def __init__(self, root: Path, source_kind='draft'):
        # 原样本有不同的正式副作用，不能借用已执行过的环境开始新的 W30 样本。
        require(not (root.resolve() / 'workflow-acceptance.json').exists(), 'new_isolated_root_required')
        super().__init__(root)
        self.path = self.root / 'workflow-test-session-acceptance.json'
        self.state = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {
            'batch': self.batch, 'scope': 'W30; owned formal HTTP; one initial input; no replay',
            'steps': {}, 'status': 'PREPARING', 'source_kind': source_kind,
            'marker': 'W30_' + self.batch, 'request_id': self.batch + '-w30-' + source_kind}
        require(self.state.get('batch') == self.batch and self.state.get('source_kind') == source_kind,
                'original_test_session_request_scope_required')

    def evidence(self, kind, value):
        save(self.root / ('workflow-test-session-' + kind + '.json'), redact(value))

    def reject(self, name, method, path, body, status, code=None):
        """只承认明确 HTTP 拒绝；传输未知不能当作通过，也不会用新键重试。"""
        existing = self.state['steps'].get(name)
        if existing:
            require(existing['status'] == 'EXPECTED_REJECTION', 'prior_write_requires_reconciliation:' + name)
            return existing
        self.state['steps'][name] = {'status': 'DISPATCHING', 'method': method, 'path': path, 'body': redact(body)}
        self.persist()
        try:
            result = self.admin.call(method, path, body)
        except SafeFailure as failure:
            prefix = f'HTTP_{status}:{method}:' + path.split('?')[0] + ':'
            if str(failure).startswith(prefix) and (code is None or str(failure) == prefix + code):
                self.state['steps'][name].update({'status': 'EXPECTED_REJECTION', 'receipt': str(failure)})
                self.persist()
                return self.state['steps'][name]
            self.state['steps'][name].update({'status': 'FAILED_OR_UNKNOWN_NO_RETRY', 'failure': str(failure)})
            self.persist()
            raise
        self.state['steps'][name].update({'status': 'UNEXPECTED_ACCEPTANCE_NO_REPLAY', 'receipt': redact(result)})
        self.persist()
        raise SafeFailure('expected_rejection_was_accepted:' + name)

    def snapshot(self):
        workflow = self.state['test_workflow_id']
        return {'draft': self.admin.call('GET', query('/builder/workflows/draft', workflowId=workflow)),
                'history': self.admin.call('GET', query('/builder/workflows/history', workflowId=workflow)),
                'workflows': self.admin.call('GET', '/builder/workflows'),
                'instances': self.admin.call('GET', '/builder/workflows/instances'),
                'agents': self.admin.call('GET', '/agents'),
                'sessions': self.admin.call('GET', '/chat/sessions'),
                'end_users': self.admin.call('GET', '/chat/end-users')}

    def permission_probes(self, receipt):
        """持有创建回执不授予普通 Agent 详情读取或借用目标创建额外 Session 的权限。"""
        baseline_key = 'ordinary_sessions_before_private_target_probe'
        if baseline_key not in self.state:
            self.state[baseline_key] = self.admin.call('GET', '/chat/sessions')
            self.persist()
        before = self.state[baseline_key]
        self.evidence('ordinary-sessions-before-private-target-probe', before)
        self.reject('test-agent-ordinary-detail-forbidden', 'GET',
                    query('/agents/detail', agentId=receipt['agent_id']), None, 403, 'workflow_test_resource_managed')
        self.reject('test-agent-ordinary-session-create-forbidden', 'POST', '/chat/sessions',
                    ordinary_session_probe_body(receipt, self.state['end_user_id']),
                    403, 'workflow_test_target_mismatch')
        after = self.admin.call('GET', '/chat/sessions')
        self.evidence('ordinary-sessions-after-private-target-probe', after)
        require(before == after, 'ordinary_private_agent_probe_created_new_session')
        readback = self.admin.call('GET', query(BASE + '/by-request', requestId=receipt['request_id']))
        require(same_identity(receipt, readback), 'ordinary_permission_probes_changed_test_session_identity')
        self.state['ordinary_permission_checks'] = {
            'receipt_agent_detail_denied': True, 'receipt_agent_new_session_denied': True,
            'ordinary_sessions_unchanged': True, 'original_test_session_identity_unchanged': True}
        self.persist()

    def provision(self):
        require('test-session-create' not in self.state['steps'], 'test_session_already_dispatched')
        # 原 provision 仅准备真实机构/模型/正式对象，不执行 chat。其账本和密钥规则完全复用。
        super().provision()
        selections = self.admin.call('GET', '/settings/model-selections')
        selected = self.step('w30-default-model-selection', self.admin, 'PUT', '/settings/model-selections',
                             {**selections, 'llm_model_config_id': self.state['model_id']})
        require(str(selected['llm_model_config_id']) == self.state['model_id'], 'default_llm_readback_required')
        workflow = self.batch + '-w30-source'
        definition = source_definition(workflow)
        draft = self.step('w30-source-create', self.admin, 'POST', '/builder/workflows',
                          {'workflow_id': workflow, 'definition': definition})
        validation = self.step('w30-source-validate', self.admin, 'POST', '/builder/workflows/draft/validate',
                               {'workflow_id': workflow})
        require(not any(item.get('severity') == 'error' for item in validation['problems']), 'source_validation_failed')
        publication = self.step('w30-source-publish', self.admin, 'POST', '/builder/workflows/publish',
                               {'workflow_id': workflow}, {'If-Match': str(draft['revision'])})
        self.step('w30-formal-installation', self.admin, 'POST', '/builder/workflows/install',
                  {'workflow_id': workflow, 'payload': {'config': {}, 'custom_prompt': '', 'bindings': EMPTY_BINDINGS}})
        definition['meta']['description'] = '原当前发布和正式安装不变，独立测试采用指定草稿修订'
        draft = self.step('w30-source-save', self.admin, 'PUT', '/builder/workflows/draft',
                         {'workflow_id': workflow, 'payload': {'definition': definition}}, {'If-Match': str(draft['revision'])})
        negatives = {}
        for kind in ('script', 'async_task'):
            name, step_name = forbidden_fixture_identity(self.batch, kind)
            forbidden = source_definition(name)
            if kind == 'script':
                content = b'export default function main(input) { return input; }\n'
                forbidden['resources'] = [{'path': 'scripts/probe.js',
                    'content_base64': base64.b64encode(content).decode('ascii'),
                    'sha256': hashlib.sha256(content).hexdigest()}]
                forbidden['nodes'][1] = {'id': 'produce', 'type': 'script', 'args': {
                    'script': 'scripts/probe.js', 'input': {}, 'output_schema': {'type': 'object'}}}
            else:
                forbidden['nodes'][1] = {'id': 'produce', 'type': 'async_task', 'timeout_seconds': 1}
            value = self.step(step_name, self.admin, 'POST', '/builder/workflows',
                              {'workflow_id': name, 'definition': forbidden})
            negatives[kind] = {'workflow_id': name, 'revision': value['revision']}
        self.state.update({'test_workflow_id': workflow, 'draft_revision': draft['revision'],
                           'publication_id': str(publication['definition_version_id']), 'negative_sources': negatives})
        self.state['production_baseline'] = self.snapshot()
        self.state['status'] = 'PROVISIONED_NO_TEST_SESSION'
        self.persist()

    def create(self):
        require(self.state['status'] == 'PROVISIONED_NO_TEST_SESSION', 'create_stage_requires_original_provision')
        self.admin = self.login()
        source = ({'kind': 'draft', 'draft_revision': self.state['draft_revision']}
                  if self.state['source_kind'] == 'draft' else
                  {'kind': 'publication', 'definition_version_id': self.state['publication_id']})
        options = self.admin.call('GET', query(BASE + '/options', workflowId=self.state['test_workflow_id'],
            **({'draftRevision': source['draft_revision']} if source['kind'] == 'draft'
               else {'definitionVersionId': source['definition_version_id']})))
        self.evidence('options', options)
        require(not options['capability_slots'], 'pure_source_must_not_need_capability_bindings')
        budget = options['budget']['recommended']
        require(set(budget) == set(BUDGET_FIELDS) and all(type(budget[key]) is int and budget[key] > 0
                and budget[key] <= options['budget']['maximum'][key] for key in BUDGET_FIELDS), 'bounded_budget_contract_required')
        body = {'request_id': self.state['request_id'], 'workflow_id': self.state['test_workflow_id'],
                'source': source, 'config': {}, 'bindings': deepcopy(EMPTY_BINDINGS),
                'user_input': '执行指定的纯赋值工作流并返回其真实结果', 'parameters': {'payload': deepcopy(PAYLOAD)}, 'budget': budget}
        self.state['test_session_request'] = body
        self.persist()
        self.reject('test-session-original-missing', 'GET', query(BASE + '/by-request', requestId=body['request_id']), None, 404, 'workflow_test_not_found')
        receipt = self.step('test-session-create', self.admin, 'POST', BASE, body)
        initial_submission(receipt)
        require(receipt['source'] == options['source'] and receipt['source']['kind'] == source['kind']
                and receipt['source']['workflow_id'] == body['workflow_id'] and receipt['source']['hash'], 'exact_source_receipt_required')
        self.state['receipt'] = receipt
        self.persist()
        readback = self.admin.call('GET', query(BASE + '/by-request', requestId=body['request_id']))
        require(same_identity(receipt, readback), 'same_request_receipt_identity_changed')
        reused = self.step('test-session-reuse-known-committed', self.admin, 'POST', BASE, body)
        require(same_identity(receipt, reused), 'same_request_created_new_resources')
        changed = deepcopy(body); changed['user_input'] += '（异内容幂等冲突探针）'
        self.reject('test-session-same-key-conflict', 'POST', BASE, changed, 409, 'workflow_test_request_conflict')
        stale = deepcopy(body); stale['request_id'] += '-stale'
        stale['source'] = {'kind': 'draft', 'draft_revision': self.state['draft_revision'] - 1}
        self.reject('test-session-source-cas-conflict', 'POST', BASE, stale, 409, 'workflow_test_source_conflict')
        self.reject('test-session-stale-has-no-receipt', 'GET', query(BASE + '/by-request', requestId=stale['request_id']),
                    None, 404, 'workflow_test_not_found')
        for kind, target in self.state['negative_sources'].items():
            self.reject('test-session-options-forbidden-' + kind, 'GET',
                        query(BASE + '/options', workflowId=target['workflow_id'], draftRevision=target['revision']), None, 422, 'workflow_test_node_unsupported')
            denied = deepcopy(body); denied.update({'request_id': body['request_id'] + '-' + kind,
                'workflow_id': target['workflow_id'], 'source': {'kind': 'draft', 'draft_revision': target['revision']}})
            self.reject('test-session-create-forbidden-' + kind, 'POST', BASE, denied, 422, 'workflow_test_node_unsupported')
            self.reject('test-session-forbidden-has-no-receipt-' + kind, 'GET',
                        query(BASE + '/by-request', requestId=denied['request_id']), None, 404, 'workflow_test_not_found')
        self.state['write_tool_restriction'] = {'status': 'NOT_TESTED',
            'reason': '本批未创建真实可写ToolVersion/Deployment，禁止编造绑定或把无候选当成拒绝证明'}
        self.permission_probes(receipt)
        require(same_identity(receipt, self.admin.call('GET', query(BASE + '/by-request', requestId=body['request_id']))),
                'negative_probes_changed_original_test_session')
        require(self.snapshot() == self.state['production_baseline'], 'production_source_or_lists_changed_during_test_creation')
        self.state['status'] = 'TEST_SESSION_READY_NO_CHAT'
        self.persist()

    def query(self):
        self.admin = self.login()
        receipt = self.admin.call('GET', query(BASE + '/by-request', requestId=self.state['request_id']))
        original = self.state.get('receipt')
        require(not original or same_identity(original, receipt), 'query_must_retain_original_identity')
        self.state['last_readback'] = receipt
        self.evidence('readback', receipt)
        self.persist()
        return receipt

    def execute(self):
        require(self.state['status'] == 'TEST_SESSION_READY_NO_CHAT', 'one_original_chat_submission_only')
        receipt = self.query()
        require(not receipt.get('run_id'), 'run_already_admitted_query_only')
        admitted = self.step('w30-initial-chat-submit', self.admin, 'POST', '/chat', initial_submission(receipt))
        require(admitted.get('execution_id'), 'formal_chat_admission_receipt_required')
        self.state.update({'status': 'EXECUTING', 'run_id': admitted['execution_id']})
        self.persist()
        self.inspect()

    def inspect(self):
        receipt = self.query()
        run = self.state.get('run_id') or receipt.get('run_id')
        require(run, 'original_run_not_found_query_only_no_submit')
        require(not receipt.get('run_id') or receipt['run_id'] == run, 'receipt_run_identity_mismatch')
        self.state['run_id'] = run
        session = receipt['session_id']; cursor = None; events = []
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            batch = self.admin.call('GET', query('/chat/events', session_id=session, limit=100,
                **({'cursor': cursor} if cursor else {})))
            events.extend(batch['events']); cursor = batch.get('next_cursor')
            self.evidence('events', events)
            if any(item['event'] in ('execution.completed', 'execution.failed', 'execution.cancelled', 'error') for item in events):
                break
            time.sleep(1)
        status = self.admin.call('GET', query('/chat/runs/status', session_id=session, run_id=run))
        root = receipt['initial_input_id']
        detail = self.admin.call('GET', query('/chat/trajectories/detail', session_id=session, root_input_id=root))
        nodes = self.admin.call('GET', query('/chat/trajectories/nodes', session_id=session,
            root_input_id=root, snapshot=detail['snapshot'], page_size=100))
        trace = {'detail': detail, 'nodes': nodes}
        history = self.admin.call('GET', query('/chat/history', session_id=session))
        usage = self.admin.call('GET', query('/billing/traces/detail', root_input_id=root))
        results = []
        for node in nodes.get('items', []):
            output = node.get('output') or {}
            if node.get('kind') == 'WORKFLOW' and output.get('ref'):
                results.append(self.admin.call('GET', query('/chat/trajectories/content',
                    session_id=session, root_input_id=root, content_ref=output['ref'])))
        for name, value in [('run', status), ('trace', trace), ('history', history), ('usage', usage), ('public-results', results)]:
            self.evidence(name, value)
        self.state['runtime_checks'] = runtime_checks(trace, history, status, results, receipt['initial_input_id'])
        self.state['status'] = 'FORMAL_RUNTIME_PASS_AWAITING_CLOSE' if all(self.state['runtime_checks'].values()) else 'FAILED_OR_INCOMPLETE_NO_REPLAY'
        self.persist()
        require(all(self.state['runtime_checks'].values()), 'formal_http_runtime_evidence_incomplete_no_replay')

    def close(self):
        require(self.state['status'] == 'FORMAL_RUNTIME_PASS_AWAITING_CLOSE', 'close_requires_original_runtime_evidence')
        original = self.query()
        self.step('test-session-close', self.admin, 'POST', BASE + '/close', {'test_session_id': original['test_session_id']})
        receipt = self.admin.call('GET', query(BASE, testSessionId=original['test_session_id']))
        deadline = time.monotonic() + 30
        while receipt.get('close_status') == 'cancelling' and time.monotonic() < deadline:
            self.evidence('close-pending', receipt)
            time.sleep(1)
            receipt = self.admin.call('GET', query(BASE, testSessionId=original['test_session_id']))
        require(same_identity(original, receipt), 'close_changed_original_identity')
        require(same_identity(receipt, self.admin.call('GET', query(BASE + '/by-request', requestId=self.state['request_id']))),
                'closed_request_lookup_changed_original_identity')
        self.evidence('close-readback', receipt)
        require(str(receipt.get('close_status', '')).lower() == 'closed', 'close_not_confirmed_read_only_query_required')
        final = self.snapshot(); self.evidence('production-readback', final)
        require(final == self.state['production_baseline'], 'production_source_or_lists_changed_after_test')
        self.state.update({'status': 'PASS_FORMAL_HTTP_WITH_WRITE_TOOL_COVERAGE_GAP', 'closed_receipt': receipt,
                           'production_source_and_lists_unchanged': True})
        self.persist()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--source-kind', choices=('draft', 'publication'), default='draft')
    parser.add_argument('--stage', required=True, choices=('provision', 'create', 'execute', 'inspect', 'query', 'close'))
    args = parser.parse_args(); acceptance = None
    try:
        acceptance = TestSessionAcceptance(args.root, args.source_kind)
        getattr(acceptance, args.stage)()
        print(json.dumps({'status': acceptance.state['status'], 'root': str(acceptance.root)}, ensure_ascii=False))
    except Exception as failure:
        if acceptance:
            acceptance.state['failure'] = str(failure) if isinstance(failure, SafeFailure) else type(failure).__name__
            acceptance.persist()
        print(json.dumps({'status': 'FAILED_OR_UNKNOWN_NO_REPLAY',
                          'failure': str(failure) if isinstance(failure, SafeFailure) else type(failure).__name__}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
