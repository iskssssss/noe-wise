"""正式 HTTP Workflow 验收：本批隔离机构内创建、发布、安装、挂载及一次有界真实模型执行。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

from workflow_live_environment import REPO, load_env, save


class SafeFailure(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise SafeFailure(code)


def redact(value):
    if isinstance(value, dict):
        return {key: '[REDACTED]' if any(part in key.lower() for part in
                ('password', 'api_key', 'access_token', 'refresh_token', 'signing_secret')) else redact(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def objects(value):
    """只解包真实结果的 JSON 封套，保留原始 number/boolean/object 类型。"""
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from objects(item)
    elif isinstance(value, list):
        for item in value:
            yield from objects(item)
    elif isinstance(value, str) and value.lstrip().startswith(('{', '[')):
        try:
            yield from objects(json.loads(value))
        except ValueError:
            pass


def typed_exports_present(values):
    expected = {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}
    return any(all(isinstance(value.get(key), dict) and value[key] == expected
                   and type(value[key]['count']) is int and value[key]['enabled'] is False
                   and isinstance(value[key]['nested'], dict)
                   for key in ('produced', 'consumed')) for value in objects(values))


class Api:
    def __init__(self, base, token=None, platform=False):
        parsed = urllib.parse.urlparse(base)
        require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port,
                'owned_loopback_service_required')
        self.base = base.rstrip('/') + '/api/v1'
        self.headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if token:
            self.headers['X-Platform-Token' if platform else 'Authorization'] = 'Bearer ' + token
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, headers=None):
        request = urllib.request.Request(self.base + path, method=method,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'),
            headers={**self.headers, **(headers or {})})
        try:
            with self.opener.open(request, timeout=45) as response:
                raw = response.read(16 * 1024 * 1024)
                status = response.status
        except urllib.error.HTTPError as failure:
            try:
                code = json.loads(failure.read(8192)).get('code', 'HTTP_ERROR')
            except (ValueError, AttributeError):
                code = 'HTTP_ERROR'
            raise SafeFailure(f'HTTP_{failure.code}:{method}:{path.split("?")[0]}:{code}') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise SafeFailure('TRANSPORT_OUTCOME_UNKNOWN:' + method + ':' + path.split('?')[0]) from None
        if status == 204:
            return None
        try:
            envelope = json.loads(raw)
            require(envelope.get('code') == 'OK', 'API_REJECTED:' + path.split('?')[0])
            return envelope['data']
        except (ValueError, KeyError, AttributeError):
            raise SafeFailure('INVALID_API_ENVELOPE:' + path.split('?')[0]) from None


class Acceptance:
    def __init__(self, root: Path, sample='initial'):
        self.root = root.resolve()
        require(self.root.is_relative_to((REPO / '.runtime-logs').resolve()), 'private_runtime_root_required')
        self.manifest = json.loads((self.root / 'environment.json').read_text(encoding='utf-8'))
        self.env = load_env(self.root / 'private.env')
        self.batch = self.manifest['batch']
        require(self.env['WF_LIVE_BATCH'] == self.batch, 'environment_owner_mismatch')
        require(sample in ('initial', 'configured', 'budgeted', 'trace-fixed'), 'known_sample_required')
        self.sample = sample
        self.path = self.root / ('workflow-acceptance.json' if sample == 'initial' else 'workflow-acceptance-' + sample + '.json')
        self.state = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {
            'batch': self.batch, 'scope': 'one isolated tenant; one real chat submission; no automatic replay',
            'steps': {}, 'status': 'PREPARING', 'marker': 'WF_RESULT_' + secrets.token_hex(12)}
        require(sample == 'initial' or self.path.exists(), 'configured_sample_requires_explicit_preparation')
        require(self.state['batch'] == self.batch, 'receipt_owner_mismatch')
        services = self.manifest['services']
        for name in ('platform-service', 'institution-service'):
            require(services.get(name, {}).get('status') == 'READY', name + '_not_ready')
        self.platform_url = services['platform-service']['base_url']
        self.institution_url = services['institution-service']['base_url']
        self.platform = self.login(True)
        self.admin = None

    def persist(self):
        save(self.path, redact(self.state))

    def login(self, platform=False):
        base = self.platform_url if platform else self.institution_url
        body = ({'username': self.env['PLATFORM_ADMIN_USERNAME'], 'password': self.env['PLATFORM_ADMIN_PASSWORD']}
                if platform else {'tenant_slug': self.env['WF_LIVE_TENANT_SLUG'],
                    'username': self.env['WF_LIVE_TENANT_USER'], 'password': self.env['WF_LIVE_TENANT_PASSWORD']})
        auth = Api(base).call('POST', '/platform/auth/login' if platform else '/auth/login', body)
        require(auth.get('mfa_state') == 'full' and auth.get('access_token'), 'formal_full_auth_required')
        return Api(base, auth['access_token'], platform)

    def step(self, name, api, method, path, body, headers=None):
        existing = self.state['steps'].get(name)
        if existing:
            require(existing['status'] == 'COMMITTED', 'prior_write_requires_reconciliation:' + name)
            return existing['receipt']
        self.state['steps'][name] = {'status': 'DISPATCHING', 'method': method, 'path': path,
                                     'body': redact(body)}
        self.persist()
        try:
            result = api.call(method, path, body, headers)
        except Exception:
            self.state['steps'][name]['status'] = 'FAILED_OR_UNKNOWN_NO_RETRY'
            self.persist()
            raise
        self.state['steps'][name].update({'status': 'COMMITTED', 'receipt': redact(result)})
        self.persist()
        return result

    def reconcile_rejected_mount(self, corrected_body, instance):
        """仅在显式操作下恢复已知 422 入参拒绝；未知写入结果永不进入此路径。"""
        previous = self.state['steps'].get('agent-mount')
        require(previous and previous['status'] == 'FAILED_OR_UNKNOWN_NO_RETRY'
                and self.state.get('failure') == 'HTTP_422:POST:/agents/capability-mounts:VALIDATION_ERROR',
                'exact_mount_validation_rejection_required')
        require('agent-mount-rejected-input-attempt-1' not in self.state['steps'],
                'mount_reconciliation_already_used')
        original = previous['body']
        expected_original = json.loads(json.dumps(corrected_body))
        expected_original['payload']['workflow_config']['workflow_instance_id'] = instance['instance_id']
        require(original == expected_original, 'only_confirmed_instance_identity_correction_allowed')
        readback = self.admin.call('GET', '/agents/capability-mounts?' + urllib.parse.urlencode(
            {'agentId': corrected_body['agent_id']}))
        require(readback['mounts'] == [], 'mount_side_effect_present_no_retry')
        self.state['steps']['agent-mount-rejected-input-attempt-1'] = previous
        self.state['mount_reconciliation'] = {
            'reason': 'HTTP 422 rejected business instance_id; formal DTO resolution requires numeric installation id',
            'original_request_outcome': 'DEFINITELY_REJECTED_NO_MOUNT',
            'mount_set_readback': readback, 'installation_readback': instance,
            'corrected_body': corrected_body, 'maximum_corrected_attempts': 1}
        del self.state['steps']['agent-mount']
        self.persist()

    def provision(self, reconcile_rejected_mount=False):
        plans = self.platform.call('GET', '/admin/tenants/plans')
        require(any(plan.get('plan_code') == 'free' for plan in plans), 'formal_free_plan_required')
        tenant = self.step('tenant', self.platform, 'POST', '/admin/tenants', {
            'tenant_name': self.batch, 'tenant_slug': self.env['WF_LIVE_TENANT_SLUG'], 'plan_code': 'free',
            'admin_username': self.env['WF_LIVE_TENANT_USER'], 'admin_password': self.env['WF_LIVE_TENANT_PASSWORD'],
            'admin_email': self.batch + '@example.test'})
        self.admin = self.login()
        me = self.admin.call('GET', '/auth/me')
        require(str(me['tenant_id']) == str(tenant['id']), 'tenant_login_identity_mismatch')
        self.state['tenant_id'] = str(tenant['id'])
        catalog = self.platform.call('GET', '/admin/models/providers')
        provider = next((item for item in catalog['providers'] if item['provider_id'] == 'deepseek'), None)
        require(provider, 'deepseek_provider_not_available')
        profile = next((item for item in provider['services'] if item['service_id'] == 'deepseek-api'), None)
        require(profile, 'deepseek_service_not_available')
        key = next((self.env[name] for name in ('SDK4_LIVE_MODEL_KEY', 'SKILLFORGE_DEEPSEEK_API_KEY', 'DEEPSEEK_API_KEY')
                    if self.env.get(name)), None)
        require(key, 'dedicated_deepseek_key_required')
        model = self.step('model', self.platform, 'POST', '/admin/models', {
            'name': self.batch + '-model', 'kind': 'LLM', 'enabled': True,
            'provider_id': provider['provider_id'], 'service_id': profile['service_id'],
            'profile_version': profile['profile_version'], 'protocol': 'openai-chat-completions',
            'authentication_scheme': 'bearer-api-key', 'endpoint': profile['endpoint'],
            'model_id': 'deepseek-flash', 'api_key': key, 'max_output_tokens': 4096,
            'supported_max_output_tokens': 32768, 'context_window_tokens': 1000000,
            'temperature': 0, 'reasoning': 'DISABLED', 'timeout_seconds': 120, 'currency': 'USD'})
        model = self.step('model-publish', self.platform, 'POST', '/admin/models/publish',
                          {'id': model['id'], 'expected_revision': model['revision']})
        self.state['model_id'] = str(model['id'])
        workflow_id = self.batch + '-workflow'
        marker = self.state['marker']
        definition = {'version': '1.0', 'entry': 'start', 'meta': {
            'name': workflow_id, 'display_name': '唯一确定性验收流程', 'version': '1.0.0',
            'capability': {'model_invocable': True, 'user_invocable': True,
                'input_schema': {'type': 'object', 'properties': {}},
                'output_schema': {'type': 'object', 'properties': {}},
                'supports_hitl': False, 'export_fields': ['messages', 'produced', 'consumed'], 'timeout_ms': 30000, 'concurrency_safe': True},
            'config_schema': [{'key': 'marker', 'label': '本批输出', 'type': 'string', 'required': True},
                              {'key': 'enabled', 'type': 'boolean', 'default': False}]},
            'nodes': [{'id': 'start', 'type': 'start'},
                      {'id': 'produce', 'type': 'assign', 'args': {'produced': 'state.ctx_extraParameters.payload'}},
                      {'id': 'consume', 'type': 'assign', 'args': {'consumed': 'state.produced'}},
                      {'id': 'card', 'type': 'card', 'card': {'card_id': 'typed-proof', 'type': 'info',
                          'title': '${config.marker}', 'body': '${config.marker}|${state.consumed.nested.label}'},
                          'render': {'marker': '${config.marker}', 'nested': {'label': '${state.consumed.nested.label}'}}},
                      {'id': 'output', 'type': 'output', 'message': '${config.marker}|${state.consumed.count}|${state.consumed.enabled}|${state.consumed.nested.label}'},
                      {'id': 'end', 'type': 'end'}],
            'edges': [{'from': 'start', 'to': 'produce'}, {'from': 'produce', 'to': 'consume'},
                      {'from': 'consume', 'to': 'card'}, {'from': 'card', 'to': 'output'}, {'from': 'output', 'to': 'end'}]}
        draft = self.step('workflow-create', self.admin, 'POST', '/builder/workflows',
                          {'workflow_id': workflow_id, 'definition': definition})
        definition['meta']['description'] = '通过正式安装参数赋值并输出，主模型只能转述真实工作流返回值'
        draft = self.step('workflow-save', self.admin, 'PUT', '/builder/workflows/draft',
                          {'workflow_id': workflow_id, 'payload': {'definition': definition}},
                          {'If-Match': str(draft['revision'])})
        self.step('workflow-layout', self.admin, 'PUT', '/builder/workflows/draft/snapshot',
                  {'workflow_id': workflow_id, 'payload': {'layout': {'start': {'x': 40, 'y': 40}}, 'viewport': {'zoom': 1, 'panX': 24, 'panY': 24}}},
                  {'If-Match': str(draft['revision'])})
        validation = self.step('workflow-validate', self.admin, 'POST', '/builder/workflows/draft/validate', {'workflow_id': workflow_id})
        require(not any(problem['severity'] == 'error' for problem in validation['problems']), 'workflow_server_validation_failed')
        publication = self.step('workflow-publish', self.admin, 'POST', '/builder/workflows/publish',
                                {'workflow_id': workflow_id}, {'If-Match': str(draft['revision'])})
        installed = self.step('workflow-install', self.admin, 'POST', '/builder/workflows/install',
                             {'workflow_id': workflow_id, 'payload': {'config': {'marker': marker, 'enabled': False},
                              'custom_prompt': '', 'bindings': {'capabilities': [], 'workflows': [], 'agents': []}}})
        instances = self.admin.call('GET', '/builder/workflows/instances')['items']
        matched = [item for item in instances if item['instance_id'] == installed['instance_id'] and item['workflow_id'] == workflow_id]
        require(len(matched) == 1 and matched[0]['config']['enabled'] is False, 'exact_installation_readback_failed')
        version = self.admin.call('GET', '/capabilities/versions/detail?versionId=' + str(publication['capability_version_id']))
        agent = self.step('agent', self.admin, 'POST', '/agents', {
            'agent_key': self.batch + '-agent', 'name': self.batch + '-agent', 'model_config_id': model['id'],
            'system_prompt': '你是验收助手。收到执行请求后，必须先调用 run_workflow 工具运行唯一已挂载的工作流。'
                             '使用工具声明中的有效handle，不自行编造。工作流完成后，最终回复只逐字返回工作流的输出内容。'
                             '禁止在工作流返回前回答完成，禁止调用其他工具。',
            'config': {'memory': {'automatic_extraction': False}}})
        self.step('agent-budget', self.admin, 'PATCH', '/agents/update', {'agent_id': str(agent['id']),
            'payload': {'execution_config': {'max_business_model_calls': 6, 'max_tool_calls': 6,
                                           'max_total_tokens': 100000, 'max_wall_time_seconds': 300}}})
        mount_body = {'agent_id': str(agent['id']),
            'payload': {'capability_type': 'WORKFLOW', 'capability_definition_id': str(version['definition_id']),
                        'workflow_config': {'workflow_instance_id': str(matched[0]['id'])}, 'priority': 0}}
        if reconcile_rejected_mount:
            self.reconcile_rejected_mount(mount_body, matched[0])
        self.step('agent-mount', self.admin, 'POST', '/agents/capability-mounts', mount_body)
        self.step('agent-publish', self.admin, 'POST', '/agents/publish', {'agent_id': str(agent['id'])})
        user = self.step('end-user', self.admin, 'POST', '/chat/end-users', {'display_name': self.batch + '-user'})
        session = self.step('session', self.admin, 'POST', '/chat/sessions',
                            {'agent_id': str(agent['id']), 'end_user_id': str(user['id'])})
        self.state.update({'status': 'PROVISIONED_NO_MODEL_CALLS', 'workflow_id': workflow_id,
                           'agent_id': str(agent['id']), 'end_user_id': str(user['id']), 'session_id': session['session_id']})
        self.persist()

    def execute(self):
        require(self.state['status'] == 'PROVISIONED_NO_MODEL_CALLS', 'one_paid_submission_only')
        self.admin = self.login()
        payload = {'agent_id': self.state['agent_id'], 'session_id': self.state['session_id'],
                   'client_message_id': self.batch + ('-input' if self.sample == 'initial' else '-' + self.sample + '-input'),
                   'content': [{'type': 'text', 'text': '请立即运行唯一已挂载的确定性验收工作流，并只返回真实输出。'}],
                   'context': {'end_user_id': self.state['end_user_id'],
                               'payload': {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}}}
        receipt = self.step('chat-submit', self.admin, 'POST', '/chat', payload)
        require(receipt.get('execution_id'), 'chat_not_admitted_no_runtime_claim')
        self.state.update({'status': 'EXECUTING', 'run_id': receipt['execution_id']})
        self.persist()
        self.inspect()

    def inspect(self):
        require(self.state.get('run_id'), 'original_run_identity_required')
        self.admin = self.login()
        session = self.state['session_id']
        cursor = None
        events = []
        deadline = time.monotonic() + 330
        while time.monotonic() < deadline:
            path = '/chat/events?' + urllib.parse.urlencode({'session_id': session, 'limit': 100, **({'cursor': cursor} if cursor else {})})
            batch = self.admin.call('GET', path)
            events.extend(batch['events'])
            cursor = batch.get('next_cursor')
            save(self.root / self.evidence_name('events'), redact(events))
            terminal = [event for event in events if event['event'] in ('execution.completed', 'error', 'execution.failed', 'execution.cancelled')]
            if terminal:
                break
            time.sleep(1)
        status = self.admin.call('GET', '/chat/runs/status?' + urllib.parse.urlencode({'session_id': session, 'run_id': self.state['run_id']}))
        root = self.state.get('input_id') or self.state['steps']['chat-submit']['receipt']['input_id']
        resource = {'session_id': session, 'root_input_id': root}
        detail = self.admin.call('GET', '/chat/trajectories/detail?' + urllib.parse.urlencode(resource))
        nodes = self.admin.call('GET', '/chat/trajectories/nodes?' + urllib.parse.urlencode(
            {**resource, 'snapshot': detail['snapshot'], 'page_size': 100}))
        trace = {'detail': detail, 'nodes': nodes}
        history = self.admin.call('GET', '/chat/history?' + urllib.parse.urlencode({'session_id': session}))
        save(self.root / self.evidence_name('runtime-status'), redact(status))
        save(self.root / self.evidence_name('trace'), redact(trace))
        save(self.root / self.evidence_name('history'), redact(history))
        executed = [node for node in nodes.get('items', []) if node.get('kind') == 'WORKFLOW'
                    and node.get('status') == 'SUCCEEDED' and node.get('run_id') == self.state['run_id']]
        actual_results = []
        for node in executed:
            output = node.get('output') or {}
            if output.get('ref'):
                actual_results.append(self.admin.call('GET', '/chat/trajectories/content?' +
                    urllib.parse.urlencode({**resource, 'content_ref': output['ref']})))
        save(self.root / self.evidence_name('public-result-content'), redact(actual_results))
        result_objects = list(objects(actual_results))
        typed = typed_exports_present(actual_results)
        card = any(value.get('title') == self.state['marker']
                   and value.get('body') == self.state['marker'] + '|typed-nested-proof' for value in result_objects)
        render = any(value.get('marker') == self.state['marker']
                     and value.get('nested') == {'label': 'typed-nested-proof'} for value in result_objects)
        complete = status.get('status') == 'completed' and any(
            event['event'] == 'execution.completed' and event.get('data', {}).get('status') == 'completed'
            for event in events)
        exact_output = any(message.get('role') == 'assistant' and message.get('content') ==
                           self.state['marker'] + '|0|false|typed-nested-proof'
                           for message in history.get('messages', []))
        self.state['runtime_checks'] = {
            'execution_completed': complete,
            'complete_node_snapshot': nodes.get('coverage') == 'COMPLETE' and not nodes.get('next_cursor'),
            'formal_workflow_action': bool(executed),
            'workflow_output_fully_recorded': any(result.get('availability') == 'FULL'
                and result.get('schema_id') == 'skillforge.runtime.workflow.engine-result.v1'
                for result in actual_results),
            'declared_nested_exports_types_preserved': typed,
            'declared_export_keys_only': any(set(value) == {'messages', 'produced', 'consumed'}
                and typed_exports_present(value) for value in result_objects),
            'installation_output_exact_in_history': exact_output,
        }
        self.state['public_result_observations'] = {'card_present_and_uses_install_config': card,
            'render_present_and_uses_install_config': render,
            'acceptance_scope': 'current trajectory snapshot and declared workflow output'}
        self.state['status'] = 'PASS_PUBLIC_DECLARED_EXPORTS' if all(self.state['runtime_checks'].values()) else 'FAILED_OR_INCOMPLETE_NO_REPLAY'
        self.persist()
        require(self.state['status'] in ('PASS', 'PASS_PUBLIC_DECLARED_EXPORTS'),
                'runtime_evidence_not_complete; inspect private original receipts without replay')

    def evidence_name(self, kind):
        return 'workflow-' + ('' if self.sample == 'initial' else self.sample + '-') + kind + '.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--stage', required=True, choices=['provision', 'execute', 'inspect'])
    parser.add_argument('--sample', choices=['initial', 'configured', 'budgeted', 'trace-fixed'], default='initial')
    parser.add_argument('--reconcile-rejected-mount', action='store_true',
                        help='显式修正一次已读回无副作用的挂载入参 422；保留原拒绝证据')
    arguments = parser.parse_args()
    acceptance = None
    try:
        acceptance = Acceptance(arguments.root, arguments.sample)
        require(not arguments.reconcile_rejected_mount or arguments.stage == 'provision',
                'mount_reconciliation_only_during_provision')
        if arguments.stage == 'provision':
            acceptance.provision(arguments.reconcile_rejected_mount)
        else:
            getattr(acceptance, arguments.stage)()
        print(json.dumps({'status': acceptance.state['status'], 'root': str(acceptance.root)}, ensure_ascii=False))
    except Exception as failure:
        if acceptance:
            acceptance.state['failure'] = str(failure) if isinstance(failure, SafeFailure) else type(failure).__name__
            acceptance.persist()
        print(json.dumps({'status': 'FAILED_OR_UNKNOWN_NO_REPLAY', 'failure': str(failure) if isinstance(failure, SafeFailure) else type(failure).__name__}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
