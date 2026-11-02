"""真实验收工具的批次与累计预算守卫测试，无网络与模型调用。"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

from live_batch import BudgetReservation, configure


class Client:
    def __init__(self, known=True):
        self.known = known
        self.calls = []

    def api(self, service, route):
        self.calls.append((service, route))
        if route.startswith('/chat/runs/status'):
            return {'status': 'completed'}
        run = route.split('runId=')[1].split('&')[0]
        return {'total': 72, 'items': [{'event_type': 'llm.chat', 'usage_event_kind': 'measured',
                'event_id': 'event-' + str(index), 'run_id': run, 'usage_source': 'PROVIDER_REPORTED' if self.known else 'UNKNOWN',
                'tokens': str(index * 125)} for index in range(1, 73)]}


class LiveBatchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / 'full-test-batch'
        self.root.mkdir()
        self.scope = {'batch_id': 'full-test-batch', 'evidence_root': str(self.root)}
        self.path = self.root / 'reservation.json'
        self.ledger = self.root / 'budget-ledger.json'
        self.receipt = {'schema_version': 1, 'run_id': 'full-project-test', 'suite': 'skills', 'round': 1,
                        'status': 'RESERVED', 'usage_known': True, 'issued_at': datetime.now(timezone.utc).isoformat(),
                        'reservation_id': str(uuid.uuid4()), 'batch_id': 'full-test-batch',
                        'evidence_root': str(self.root), 'ledger_path': str(self.ledger),
                        'max_input_turns': 2, 'max_tokens': 2000000, 'max_seconds': 10800}
        self.path.write_text(json.dumps(self.receipt), encoding='utf-8')
        self.ledger.write_text(json.dumps({'schema_version': 1, 'run_id': 'full-project-test', 'rounds': 3,
                                         'reservations': [self.receipt], 'stop_new_dispatch': False}), encoding='utf-8')
        self.env = patch.dict(os.environ, {'LIVE_BUDGET_RESERVATION': str(self.path), 'FULL_TEST_RUN_ID': 'full-project-test'})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temporary.cleanup()

    def body(self, number):
        return {'client_message_id': f'input-{number}', 'session_id': 'isolated-session', 'agent_id': 'isolated-agent'}

    def test_two_real_measurement_sequences_exhaust_input_cap_and_replay_is_rejected(self):
        guard = BudgetReservation(self.scope)
        client = Client()
        for number in range(1, 3):
            guard.before_chat(self.body(number), client)
            guard.after_chat(self.body(number), {'execution_id': f'run-{number}'})
            guard.settle(client)
        state = json.loads(guard.observations.read_text())
        self.assertEqual(657000, state['observed_tokens'])
        self.assertEqual(2, state['observed_input_turns'])
        self.assertTrue(state['usage_known'])
        self.assertEqual(72, len(state['dispatches']['input-1']['measured_event_ids']))
        with self.assertRaisesRegex(RuntimeError, 'BUDGET_EXHAUSTED'):
            guard.before_chat(self.body(3), client)
        with self.assertRaisesRegex(RuntimeError, 'QUERY_ONLY_NO_POST_REPLAY'):
            guard.before_chat(self.body(1), client)

    def test_unknown_submission_and_unknown_actual_usage_each_stop_new_dispatch(self):
        guard = BudgetReservation(self.scope)
        guard.before_chat(self.body(1), Client())
        with self.assertRaisesRegex(RuntimeError, 'UNRESOLVED_QUERY_ONLY'):
            guard.before_chat(self.body(2), Client())
        guard.after_chat(self.body(1), {'execution_id': 'run-1'})
        guard.settle(Client(known=False))
        with self.assertRaisesRegex(RuntimeError, 'UNRESOLVED_QUERY_ONLY'):
            guard.before_chat(self.body(2), Client(known=False))
        self.assertFalse(json.loads(guard.observations.read_text())['usage_known'])
        guard.settle(Client())
        guard.before_chat(self.body(2), Client())

    def test_registered_scope_cumulative_stop_and_exclusive_lock(self):
        guard = BudgetReservation(self.scope)
        ledger = json.loads(self.ledger.read_text())
        ledger['stop_new_dispatch'] = True
        self.ledger.write_text(json.dumps(ledger))
        guard.settle(Client())
        with self.assertRaisesRegex(RuntimeError, 'SUPERVISOR_STOPPED'):
            guard.before_chat(self.body(1), Client())
        ledger['stop_new_dispatch'] = False
        self.ledger.write_text(json.dumps(ledger))
        lock = guard.observations.with_suffix('.lock')
        lock.write_text('other-agent')
        with self.assertRaisesRegex(RuntimeError, 'LOCKED_NO_PARALLEL'):
            guard.before_chat(self.body(1), Client())
        lock.unlink()
        with self.assertRaisesRegex(RuntimeError, 'SCOPE_OR_USAGE_UNKNOWN'):
            BudgetReservation({'batch_id': 'another-batch', 'evidence_root': str(self.root)})
        ledger['reservations'] = []
        self.ledger.write_text(json.dumps(ledger))
        with self.assertRaisesRegex(RuntimeError, 'NOT_REGISTERED'):
            BudgetReservation(self.scope)

    def test_explicit_batch_inputs_do_not_consume_or_overwrite_historical_paths(self):
        report = self.root / 'packages.json'
        report.write_text('{}')
        args = type('Args', (), {'batch_id': 'full-test-batch', 'evidence_root': self.root / 'candidate', 'packages_report': report})()
        with patch.dict(os.environ, {'FULL_TEST_BATCH': 'full-test-batch'}):
            scope = configure(args, 'strengthened-candidate')
            self.assertEqual('skill-live-full-test-batch-strengthened-candidate', scope['agent_key'])
            self.assertEqual(scope, configure(args, 'strengthened-candidate'))
            with self.assertRaisesRegex(RuntimeError, 'SCOPE_CHANGED'):
                configure(args, 'general')


if __name__ == '__main__':
    unittest.main()
