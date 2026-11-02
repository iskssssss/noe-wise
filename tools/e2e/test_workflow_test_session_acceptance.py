"""W30 脚本纯逻辑检查；不创建环境、不登录、不发送 HTTP。"""
from copy import deepcopy
import json
import re
import unittest
import tempfile
from pathlib import Path

from workflow_live_acceptance import Acceptance
from workflow_test_session_acceptance import (
    TestSessionAcceptance, SafeFailure, initial_submission, runtime_checks,
    same_identity, source_definition, ordinary_session_probe_body, forbidden_fixture_identity,
)


def receipt():
    return {'test_session_id': 'test-1', 'request_id': 'request-1', 'session_id': 'session-1',
            'agent_id': '9007199254740995', 'end_user_id': 'user-1', 'initial_input_id': 'input-1',
            'initial_message': '原始受理文本', 'source': {'kind': 'draft', 'workflow_id': 'source',
            'draft_revision': 2, 'definition_version_id': None, 'hash': 'frozen-source'}}


class LogicTest(unittest.TestCase):
    def test_initial_submission_uses_only_reserved_formal_identity(self):
        payload = initial_submission(receipt())
        self.assertEqual(payload['client_message_id'], 'input-1')
        self.assertEqual(payload['content'], [{'type': 'text', 'text': '原始受理文本'}])
        self.assertEqual(payload['context'], {'end_user_id': 'user-1'})
        self.assertEqual(payload['references'], [])
        self.assertNotIn('message', payload)
        self.assertNotIn('input_id', payload['context'])

    def test_same_key_must_retain_all_resource_and_source_identities(self):
        original = receipt()
        updated = deepcopy(original); updated.update({'run_id': 'run-1', 'close_status': 'CLOSED'})
        self.assertTrue(same_identity(original, updated))
        updated['source']['hash'] = 'another-source'
        self.assertFalse(same_identity(original, updated))

    def test_ordinary_session_probe_uses_private_agent_but_a_formal_existing_user(self):
        self.assertEqual(ordinary_session_probe_body(receipt(), 'formal-user-2'),
                         {'agent_id': '9007199254740995', 'end_user_id': 'formal-user-2'})
        with self.assertRaises(SafeFailure):
            ordinary_session_probe_body({}, 'formal-user-2')

    def test_permission_probes_use_formal_routes_and_only_mark_success_after_readback(self):
        acceptance = object.__new__(TestSessionAcceptance)
        acceptance.state = {'steps': {}, 'end_user_id': 'formal-user-2'}
        acceptance.persist = lambda: None
        evidence = []
        acceptance.evidence = lambda name, value: evidence.append((name, value))
        class PermissionApi:
            def __init__(self):
                self.calls = []
            def call(self, method, path, body=None):
                self.calls.append((method, path, body))
                if path.startswith('/agents/detail?'):
                    raise SafeFailure('HTTP_403:GET:/agents/detail:workflow_test_resource_managed')
                if path == '/chat/sessions' and method == 'POST':
                    raise SafeFailure('HTTP_403:POST:/chat/sessions:workflow_test_target_mismatch')
                if path == '/chat/sessions':
                    return [{'session_id': 'formal-session'}]
                if path.startswith('/builder/workflows/test-sessions/by-request?'):
                    return receipt()
                raise AssertionError('不允许额外协议: ' + path)
        acceptance.admin = api = PermissionApi()
        acceptance.permission_probes(receipt())
        self.assertIn(('GET', '/agents/detail?agentId=9007199254740995', None), api.calls)
        self.assertIn(('POST', '/chat/sessions', {'agent_id': '9007199254740995', 'end_user_id': 'formal-user-2'}), api.calls)
        self.assertTrue(all(acceptance.state['ordinary_permission_checks'].values()))
        self.assertEqual(evidence[0][1], evidence[1][1])

    def test_ordinary_session_probe_unknown_is_not_replayed_or_counted_as_denied(self):
        acceptance = object.__new__(TestSessionAcceptance)
        acceptance.state = {'steps': {}, 'end_user_id': 'formal-user-2'}
        acceptance.persist = lambda: None
        acceptance.evidence = lambda *_args: None
        class UnknownApi:
            writes = 0
            def call(self, method, path, _body=None):
                if path.startswith('/agents/detail?'):
                    raise SafeFailure('HTTP_403:GET:/agents/detail:workflow_test_resource_managed')
                if method == 'POST':
                    self.writes += 1
                    raise SafeFailure('TRANSPORT_OUTCOME_UNKNOWN:POST:/chat/sessions')
                return []
        acceptance.admin = api = UnknownApi()
        for _ in range(2):
            with self.assertRaises(SafeFailure):
                acceptance.permission_probes(receipt())
        self.assertEqual(api.writes, 1)
        self.assertNotIn('ordinary_permission_checks', acceptance.state)

    def test_source_uses_typed_parameter_state_and_no_effectful_nodes(self):
        definition = source_definition('owned-source')
        self.assertEqual([node['type'] for node in definition['nodes']], ['start', 'assign', 'assign', 'output', 'end'])
        self.assertEqual(definition['nodes'][1]['args'], {'produced': 'state.ctx_payload'})
        self.assertIs(definition['meta']['capability']['model_invocable'], False)
        self.assertIs(definition['meta']['capability']['user_invocable'], True)
        self.assertNotIn('resources', definition)

    def test_forbidden_fixture_uses_legal_slug_and_a_distinct_corrected_step(self):
        workflow_id, step_name = forbidden_fixture_identity('sf-wf-live-925043f002', 'async_task')
        self.assertEqual(workflow_id, 'sf-wf-live-925043f002-w30-forbidden-async-task')
        self.assertRegex(workflow_id, re.compile(r'^[a-z][a-z0-9-]*$'))
        self.assertEqual(step_name, 'w30-forbidden-source-async-task')
        self.assertNotEqual(step_name, 'w30-forbidden-source-async_task')

    def test_corrected_fixture_does_not_replay_or_replace_the_original_failed_step(self):
        acceptance = object.__new__(TestSessionAcceptance)
        old_name = 'w30-forbidden-source-async_task'
        original = {'status': 'FAILED_OR_UNKNOWN_NO_RETRY', 'method': 'POST', 'path': '/builder/workflows',
                    'body': {'workflow_id': 'sf-wf-live-925043f002-w30-forbidden-async_task'}}
        acceptance.state = {'steps': {old_name: deepcopy(original)}}
        acceptance.persist = lambda: None
        workflow_id, corrected_name = forbidden_fixture_identity('sf-wf-live-925043f002', 'async_task')
        class Api:
            calls = 0
            def call(self, _method, _path, body, _headers):
                self.calls += 1
                return {'workflow_id': body['workflow_id'], 'revision': 1}
        api = Api()
        first = acceptance.step(corrected_name, api, 'POST', '/builder/workflows', {'workflow_id': workflow_id})
        second = acceptance.step(corrected_name, api, 'POST', '/builder/workflows', {'workflow_id': workflow_id})
        self.assertEqual(first, second)
        self.assertEqual(api.calls, 1)
        with self.assertRaises(SafeFailure):
            acceptance.step(old_name, api, 'POST', '/builder/workflows', original['body'])
        self.assertEqual(api.calls, 1)
        self.assertEqual(acceptance.state['steps'][old_name], original)

    def test_unknown_write_is_never_replayed_by_original_step(self):
        acceptance = object.__new__(TestSessionAcceptance)
        acceptance.state = {'steps': {}}
        acceptance.persist = lambda: None
        class UnknownApi:
            calls = 0
            def call(self, *_args):
                self.calls += 1
                raise SafeFailure('TRANSPORT_OUTCOME_UNKNOWN:POST:/owned')
        api = UnknownApi()
        for _ in range(2):
            with self.assertRaises(SafeFailure):
                acceptance.step('original-write', api, 'POST', '/owned', {'request_id': 'original'})
        self.assertEqual(api.calls, 1)
        self.assertEqual(acceptance.state['steps']['original-write']['status'], 'FAILED_OR_UNKNOWN_NO_RETRY')

    def test_unknown_rejection_probe_is_not_an_expected_rejection_or_replayed(self):
        acceptance = object.__new__(TestSessionAcceptance)
        acceptance.state = {'steps': {}}
        acceptance.persist = lambda: None
        class UnknownApi:
            calls = 0
            def call(self, *_args):
                self.calls += 1
                raise SafeFailure('TRANSPORT_OUTCOME_UNKNOWN:POST:/owned')
        acceptance.admin = api = UnknownApi()
        for _ in range(2):
            with self.assertRaises(SafeFailure):
                acceptance.reject('original-probe', 'POST', '/owned', {'request_id': 'original'}, 409)
        self.assertEqual(api.calls, 1)

    def test_workflow_snapshot_counts_actions_and_rejects_model_decisions(self):
        typed = {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}
        result = {'messages': [{'role': 'assistant', 'content': '0|false|typed-nested-proof'}],
                  'produced': deepcopy(typed), 'consumed': deepcopy(typed)}
        trace = {'detail': {'summary': {'root_input_id': 'input-1'}, 'usage_summary': {'calls': 0}},
                 'nodes': {'coverage': 'COMPLETE', 'next_cursor': None, 'items': [
                     {'kind': 'WORKFLOW', 'action_id': 'workflow-1', 'status': 'SUCCEEDED', 'root_input_id': 'input-1'}]}}
        content = {'availability': 'FULL', 'schema_id': 'skillforge.runtime.workflow.engine-result.v1', 'value': result}
        checks = runtime_checks(trace, {'messages': [{'role': 'assistant', 'content': json.dumps(result)}]},
                                {'status': 'completed'}, [content], 'input-1')
        self.assertTrue(all(checks.values()))
        trace['nodes']['items'].append({'kind': 'LLM', 'action_id': 'model-1', 'root_input_id': 'input-1'})
        self.assertFalse(runtime_checks(trace, {}, {'status': 'completed'}, [content], 'input-1')['no_model_decision_action'])
        result['produced']['count'] = False
        self.assertFalse(runtime_checks(trace, {}, {'status': 'completed'}, [content], 'input-1')['declared_typed_exports'])
        trace['nodes']['next_cursor'] = 'additional-page'
        self.assertFalse(runtime_checks(trace, {}, {'status': 'completed'}, [content], 'input-1')['complete_node_snapshot'])

    def test_real_agent_inspection_uses_current_trace_and_rejects_non_exact_answer(self):
        typed = {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}
        output = {'messages': [], 'produced': typed, 'consumed': typed}
        class ReadOnlyApi:
            def __init__(self, answer):
                self.answer = answer
                self.paths = []
            def call(self, method, path, body=None, headers=None):
                self.paths.append((method, path))
                if path.startswith('/chat/events?'):
                    return {'events': [{'event': 'execution.completed', 'data': {'status': 'completed'}}]}
                if path.startswith('/chat/runs/status?'): return {'status': 'completed'}
                if path.startswith('/chat/trajectories/detail?'): return {'snapshot': 'snapshot-1'}
                if path.startswith('/chat/trajectories/nodes?'):
                    return {'coverage': 'COMPLETE', 'next_cursor': None, 'items': [
                        {'kind': 'WORKFLOW', 'status': 'SUCCEEDED', 'run_id': 'run-1', 'output': {'ref': 'ref-1'}}]}
                if path.startswith('/chat/trajectories/content?'):
                    return {'availability': 'FULL', 'schema_id': 'skillforge.runtime.workflow.engine-result.v1', 'value': output}
                if path.startswith('/chat/history?'): return {'messages': [{'role': 'assistant', 'content': self.answer}]}
                raise AssertionError(path)
        for answer, expected in [(json.dumps(output), False), ('marker|0|false|typed-nested-proof', True)]:
            with tempfile.TemporaryDirectory() as directory:
                api = ReadOnlyApi(answer)
                acceptance = object.__new__(Acceptance)
                acceptance.root = Path(directory); acceptance.sample = 'initial'
                acceptance.admin = api; acceptance.login = lambda: api
                acceptance.state = {'session_id': 'session-1', 'run_id': 'run-1', 'input_id': 'input-1',
                                    'marker': 'marker', 'steps': {}}
                acceptance.persist = lambda: None
                if expected: acceptance.inspect()
                else:
                    with self.assertRaises(SafeFailure): acceptance.inspect()
                self.assertEqual(acceptance.state['runtime_checks']['installation_output_exact_in_history'], expected)
                self.assertTrue(all(method == 'GET' for method, _ in api.paths))
                self.assertFalse(any('/chat/sessions/trace' in path for _, path in api.paths))

    def test_final_text_must_be_the_same_declared_json_not_a_substring_or_invented_answer(self):
        typed = {'count': 0, 'enabled': False, 'nested': {'label': 'typed-nested-proof'}}
        exported = {'messages': [{'role': 'assistant', 'content': '0|false|typed-nested-proof'}],
                    'produced': deepcopy(typed), 'consumed': deepcopy(typed)}
        changed_type = deepcopy(exported); changed_type['produced']['count'] = False
        for text in ('0|false|typed-nested-proof', '日志里出现0|false|typed-nested-proof',
                     json.dumps({**exported, 'extra': 'invented'}), json.dumps(changed_type)):
            checks = runtime_checks({}, {'messages': [{'role': 'assistant', 'content': text}]}, {}, [exported], 'input-1')
            self.assertFalse(checks['history_matches_declared_output'])
            self.assertFalse(checks['output_node_message_exact'])


if __name__ == '__main__':
    unittest.main()
