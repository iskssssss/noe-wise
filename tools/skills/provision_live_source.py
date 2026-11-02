"""通过本批真实管理 API 创建精确14工具来源 Agent；不提交聊天或调用模型。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/chat_regression'))
from client import Api
from live_environment import validate
from live_support import save

TOOLS = ('code_execute', 'finalize_task_outputs', 'render_artifact',
         'list_artifacts', 'read_context_reference', 'http_request', 'get_current_time',
         'read_image', 'run_code', 'browser_start', 'browser_call', 'browser_stop', 'browser_tasks')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--batch-id', required=True)
    args = parser.parse_args()
    root, evidence = args.root.resolve(), args.evidence.resolve()
    if not root.is_relative_to(REPO / '.runtime-logs') or not evidence.is_relative_to(REPO / '.agent-work-state'):
        raise RuntimeError('PRIVATE_OWNED_EVIDENCE_REQUIRED')
    state = json.loads((root / 'environment.json').read_text(encoding='utf-8'))
    provision = json.loads((root / 'provision.json').read_text(encoding='utf-8'))
    env = dict(line.split('=', 1) for line in (root / 'isolated.env').read_text(encoding='utf-8').splitlines()
               if '=' in line and not line.startswith('#'))
    origins = {name: state['services'][service]['base_url'] for name, service in (
        ('platform', 'platform-service'), ('institution', 'institution-service'), ('gateway', 'gateway'))}
    scope = {'environment_manifest': str(root / 'environment.json'),
             'postgres_container_id': state['containers'][0], 'database_name': 'skillforge'}
    validate(scope, origins)
    evidence.mkdir(parents=True, exist_ok=True)
    journal_path = evidence / 'creation.private.json'
    journal = json.loads(journal_path.read_text(encoding='utf-8')) if journal_path.exists() else {'batch_id': args.batch_id, 'operations': {}}
    if journal['batch_id'] != args.batch_id:
        raise RuntimeError('CREATION_BATCH_CHANGED')

    def write(api, key, route, body):
        operations = journal['operations']
        if key in operations:
            entry = operations[key]
            if entry['route'] != route or entry['request'] != body:
                raise RuntimeError('CREATION_INPUT_CHANGED')
            if entry['status'] != 'observed':
                raise RuntimeError('CREATION_UNKNOWN_QUERY_ONLY')
            return entry['response']
        entry = {'route': route, 'request': body, 'status': 'unknown'}
        operations[key] = entry
        save(journal_path, journal)
        response = api.request('POST', route, body, _rate_retry=True)
        entry.update(status='observed', response=response)
        save(journal_path, journal)
        return response

    # 凭据来自本批私有文件，登录请求体不登记到回执中。
    platform_auth = Api(origins['platform'] + '/api/v1').request('POST', '/platform/auth/login',
        {'username': env['SF_PLATFORM_USER'], 'password': env['SF_PLATFORM_PASSWORD']}, _rate_retry=True)
    institution_auth = Api(origins['institution'] + '/api/v1').request('POST', '/auth/login',
        {'tenant_slug': env['SF_TENANT'], 'username': env['SF_USER'], 'password': env['SF_PASSWORD']}, _rate_retry=True)
    api = Api(origins['institution'] + '/api/v1', institution_auth['access_token'])
    definitions = api.request('GET', '/capabilities/definitions')
    providers = api.request('GET', '/capabilities/platform-tools')
    agent_key = args.batch_id + '-live-source'
    existing = [item for item in api.request('GET', '/agents')['items'] if item['agent_key'] == agent_key]
    if existing and 'agent' not in journal['operations']:
        raise RuntimeError('UNOWNED_SOURCE_AGENT')
    agent = write(api, 'agent', '/agents', {'agent_key': agent_key, 'name': '本批复杂验收工具来源',
        'model_config_id': str(provision['models']['llm']['id']),
        'system_prompt': '仅处理本批测试对象和合成资料。调用精确挂载工具，以真实回执为依据。结果 unknown 时只查询，不能重放。',
        'config': {'memory': {'automatic_extraction': False}}})
    aid = str(agent['id'])
    for name in TOOLS:
        definition = next(item for item in definitions if item['capability_key'] == 'tool:' + name)
        provider = next(item for item in providers if item['name'] == name)
        versions = api.request('GET', '/capabilities/definitions/versions?definitionId=' + str(definition['id']))
        version = next(item for item in versions if item['status'] == 'PUBLISHED' and item['content_hash'] == provider['content_hash'])
        deployment_key = agent_key + '-' + name
        deployments = api.request('GET', '/capabilities/deployments')
        if any(item['deployment_key'] == deployment_key for item in deployments) and 'deployment:' + name not in journal['operations']:
            raise RuntimeError('UNOWNED_SOURCE_DEPLOYMENT')
        deployment = write(api, 'deployment:' + name, '/capabilities/deployments', {
            'deployment_key': deployment_key, 'tool_version_id': str(version['id']), 'provider_type': 'PLATFORM',
            'provider_ref': name, 'environment': 'TEST', 'status': 'ACTIVE'})
        write(api, 'mount:' + name, '/agents/capability-mounts', {'agent_id': aid, 'payload': {
            'capability_type': 'TOOL', 'capability_definition_id': str(definition['id']),
            'capability_version_id': str(version['id']), 'priority': 0, 'status': 'ACTIVE',
            'tool_config': {'tool_deployment_id': deployment['deployment_key'], 'tool_deployment_revision': deployment['revision']}}})
    published = write(api, 'publish', '/agents/publish', {'agent_id': aid})
    if published['status'] != 'published':
        raise RuntimeError('SOURCE_NOT_PUBLISHED')
    mounts = api.request('GET', '/agents/capability-mounts?agentId=' + aid)['mounts']
    if len(mounts) != len(TOOLS):
        raise RuntimeError('SOURCE_MOUNT_COUNT_MISMATCH')
    user = write(api, 'end-user', '/chat/end-users', {'display_name': args.batch_id + '复杂测试终端用户'})
    session = write(api, 'session', '/chat/sessions', {'agent_id': aid, 'end_user_id': str(user['id'])})
    pair = write(api, 'pair', '/chat/browser/pair', {'session_id': session['session_id']})
    fixture = {**scope, 'origins': origins, 'tenant_id': str(provision['tenants'][0]['id']),
        'end_user_id': str(user['id']), 'model_config_id': str(provision['models']['llm']['id']),
        'source_agent_id': aid, 'platform_token': platform_auth['access_token'],
        'institution_token': institution_auth['access_token'], 'browser_session_id': session['session_id'],
        'authentication': {name: {'refresh_token': auth['refresh_token'], 'expires_at': time.time() + auth['expires_in']}
                           for name, auth in (('platform', platform_auth), ('institution', institution_auth))}}
    fixture_path = evidence / 'fixture.private.json'
    save(fixture_path, fixture)
    save(evidence / 'browser-pair.private.json', pair)
    save(evidence / 'source-mounts.json', mounts)
    print(json.dumps({'status': 'READY', 'fixture': str(fixture_path), 'source_agent_id': aid,
                      'end_user_id': str(user['id']), 'session_id': session['session_id'], 'mounts': len(mounts), 'paid_model_calls': 0}))


if __name__ == '__main__':
    main()
